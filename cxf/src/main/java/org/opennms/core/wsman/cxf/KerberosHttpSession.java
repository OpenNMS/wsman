/*
 * Copyright (C) The OpenNMS Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opennms.core.wsman.cxf;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.ietf.jgss.GSSException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Kerberos-encrypted WS-Man transport session per MS-WSMV §2.2.9.1: one TCP connection,
 * the GSS context bound to it, and the HTTP/1.1 exchange logic, owned together.
 *
 * <p>Windows HTTP.sys binds the Kerberos session established by the AP-REQ/AP-REP
 * Negotiate handshake to the TCP connection it arrived on; encrypted bodies on any other
 * connection are rejected with 401. Earlier revisions of this library performed the
 * handshake through {@code HttpURLConnection} and relied on the JVM's global keep-alive
 * pool handing CXF's conduit the same socket back — which held only by luck for
 * sequential use and broke outright for concurrent use. This class removes the pool from
 * the picture: the handshake and every encrypted exchange are written on a socket this
 * object owns, so the session-to-connection binding is guaranteed by construction.
 *
 * <p>All exchanges are synchronized: one connection carries one request at a time, which
 * is inherent to the protocol (the encryption context is per-connection). Concurrent
 * callers serialize here. If the connection is lost (keep-alive expiry, network blip),
 * the next exchange transparently reconnects and re-runs the handshake; the JAAS login
 * (and TGT) in {@link GSSContextManager} is reused, so a reconnect costs an AP-REQ
 * exchange, not a full authentication.
 */
public class KerberosHttpSession implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(KerberosHttpSession.class);

    private static final int MAX_HANDSHAKE_LEGS = 10;
    private static final int DEFAULT_CONNECT_TIMEOUT = 30_000;
    private static final int DEFAULT_RECEIVE_TIMEOUT = 60_000;
    /** Cap on response body size (64 MB) — guards against a broken/hostile length header. */
    private static final int MAX_RESPONSE_BODY = 64 * 1024 * 1024;

    private final String host;
    private final int port;
    private final String path;
    private final boolean https;
    private final SSLSocketFactory sslSocketFactory;
    private final boolean verifyHostname;
    private final GSSContextManager gss;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    /** Number of successful exchanges on the current connection; > 0 means a write
     *  failure is probably a stale keep-alive and a single retry is safe. */
    private int exchangesOnConnection;

    /**
     * @param url               the WS-Man endpoint
     * @param gss               the GSS layer (owned by this session: closed on {@link #close()})
     * @param sslSocketFactory  factory for HTTPS connections, or {@code null} for the JVM default
     * @param verifyHostname    when true (strict SSL), the TLS handshake performs HTTPS endpoint
     *                          identification against the certificate
     */
    public KerberosHttpSession(URL url, GSSContextManager gss,
                               SSLSocketFactory sslSocketFactory, boolean verifyHostname) {
        this.host = url.getHost();
        this.port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
        String p = url.getPath();
        this.path = (p == null || p.isEmpty()) ? "/" : p;
        this.https = "https".equalsIgnoreCase(url.getProtocol());
        this.sslSocketFactory = sslSocketFactory;
        this.verifyHostname = verifyHostname;
        this.gss = gss;
    }

    /**
     * The outcome of one encrypted request/response exchange, after decryption and
     * cleartext-policy checks. {@code body} is the plaintext SOAP when {@code decrypted}
     * is true, or the raw cleartext body of an error response otherwise.
     */
    public static final class Response {
        private final int statusCode;
        private final String reasonPhrase;
        private final Map<String, List<String>> headers;
        private final byte[] body;
        private final String contentType;
        private final boolean decrypted;

        Response(int statusCode, String reasonPhrase, Map<String, List<String>> headers,
                 byte[] body, String contentType, boolean decrypted) {
            this.statusCode = statusCode;
            this.reasonPhrase = reasonPhrase;
            this.headers = headers;
            this.body = body;
            this.contentType = contentType;
            this.decrypted = decrypted;
        }

        public int getStatusCode() { return statusCode; }
        public String getReasonPhrase() { return reasonPhrase; }
        public Map<String, List<String>> getHeaders() { return headers; }
        public byte[] getBody() { return body; }
        public String getContentType() { return contentType; }
        public boolean isDecrypted() { return decrypted; }
    }

    /**
     * Sends one SOAP request (UTF-8 bytes) encrypted per MS-WSMV §2.2.9.1 and returns the
     * classified response. Establishes the connection and the Kerberos session on first
     * use; transparently reconnects and re-handshakes once on a stale keep-alive
     * connection or on a 401 that indicates the server dropped the session binding.
     *
     * @param soapUtf8        the plaintext SOAP envelope
     * @param connectTimeout  socket connect timeout in ms ({@code <= 0} for the 30s default)
     * @param receiveTimeout  socket read timeout in ms ({@code <= 0} for the 60s default)
     */
    public synchronized Response sendEncrypted(byte[] soapUtf8, int connectTimeout, int receiveTimeout)
            throws IOException {
        int cTimeout = connectTimeout > 0 ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        int rTimeout = receiveTimeout > 0 ? receiveTimeout : DEFAULT_RECEIVE_TIMEOUT;

        RawResponse raw;
        try {
            raw = attempt(soapUtf8, cTimeout, rTimeout);
        } catch (StaleConnectionException e) {
            // The connection served earlier exchanges and died on write/first-read: the
            // server closed an idle keep-alive socket. Reconnect, re-handshake, resend.
            // The request was re-encrypted under the new context by attempt().
            LOG.debug("Kerberos session connection went stale, reconnecting: {}", e.getCause().toString());
            invalidateConnection();
            raw = attempt(soapUtf8, cTimeout, rTimeout);
        }

        if (raw.statusCode == 401) {
            // The server no longer honors this connection's session binding (or an
            // intermediary recycled the connection). One fresh handshake, one resend.
            LOG.debug("Kerberos session got 401 on an established session, re-handshaking");
            invalidateConnection();
            raw = attempt(soapUtf8, cTimeout, rTimeout);
        }

        return classify(raw);
    }

    /**
     * One full try: ensure connection + established context, encrypt, exchange.
     * Encryption must happen inside the attempt because a reconnect creates a new GSS
     * context with a new session key: bytes wrapped under the old context are useless.
     */
    private RawResponse attempt(byte[] soapUtf8, int connectTimeout, int receiveTimeout) throws IOException {
        ensureSession(connectTimeout, receiveTimeout);

        byte[] multipart;
        try {
            multipart = WinRMEncryptedMultipart.build(gss.wrapWinRM(soapUtf8), soapUtf8.length);
        } catch (GSSException e) {
            throw new IOException("Kerberos encryption of the outbound message failed", e);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", WinRMEncryptedMultipart.CONTENT_TYPE);
        headers.put("Content-Length", String.valueOf(multipart.length));

        boolean freshConnection = exchangesOnConnection == 0;
        try {
            socket.setSoTimeout(receiveTimeout);
            writeRequest(out, headers, multipart);
            RawResponse raw = readResponse(in);
            exchangesOnConnection++;
            if (raw.connectionClose) {
                // Server is done with this connection; the session binding dies with it.
                invalidateConnection();
            }
            return raw;
        } catch (SocketTimeoutException e) {
            // The request may have reached the server; retrying could duplicate it.
            invalidateConnection();
            throw e;
        } catch (IOException e) {
            invalidateConnection();
            if (!freshConnection) {
                throw new StaleConnectionException(e);
            }
            throw e;
        }
    }

    /**
     * Applies the MS-WSMV response policy:
     * <ul>
     *   <li>Encrypted multipart bodies are decrypted regardless of status code (Windows
     *       sends encrypted fault bodies on an established session).</li>
     *   <li>Cleartext bodies on an error response (4xx/5xx) pass through so the SOAP
     *       fault chain can produce a useful diagnostic: 401 challenges and
     *       protocol-error 500s legitimately arrive unencrypted.</li>
     *   <li>Cleartext on a success response is a downgrade: with message encryption
     *       enabled (typically over plain HTTP), the multipart framing is the only
     *       confidentiality and integrity on the wire, so a cleartext SOAP result must
     *       never be consumed as authentic. Hard failure.</li>
     * </ul>
     */
    private Response classify(RawResponse raw) throws IOException {
        if (WinRMEncryptedMultipart.isEncrypted(raw.body)) {
            byte[] soapUtf8;
            try {
                soapUtf8 = gss.unwrapWinRM(WinRMEncryptedMultipart.extractEncryptedSection(raw.body));
            } catch (GSSException e) {
                throw new IOException("Kerberos decryption of the response failed (HTTP "
                    + raw.statusCode + ", " + raw.body.length + " bytes)", e);
            }
            LOG.debug("Kerberos-decrypted response: HTTP {}, {} encrypted bytes -> {} SOAP bytes",
                raw.statusCode, raw.body.length, soapUtf8.length);
            return new Response(raw.statusCode, raw.reasonPhrase, raw.headers, soapUtf8,
                "application/soap+xml;charset=UTF-8", true);
        }

        if (raw.statusCode >= 400) {
            String contentType = firstHeader(raw.headers, "Content-Type");
            if (contentType == null) {
                contentType = "application/soap+xml;charset=UTF-8";
            }
            return new Response(raw.statusCode, raw.reasonPhrase, raw.headers, raw.body, contentType, false);
        }

        throw new IOException("Expected a Kerberos-encrypted response but received cleartext"
            + " (HTTP " + raw.statusCode + ", " + raw.body.length + " byte body)."
            + " Refusing to process an unauthenticated response on an encrypted session.");
    }

    /**
     * Ensures the socket is connected and the GSS context on it is established, running
     * the Negotiate handshake (bare POSTs with Authorization/WWW-Authenticate token
     * exchange, mirroring pywinrm's setup) when needed.
     */
    private void ensureSession(int connectTimeout, int receiveTimeout) throws IOException {
        if (socket != null && !socket.isClosed() && gss.isEstablished()) {
            return;
        }
        invalidateConnection();
        connect(connectTimeout, receiveTimeout);
        try {
            handshake(receiveTimeout);
        } catch (IOException | RuntimeException e) {
            invalidateConnection();
            throw e;
        }
    }

    private void connect(int connectTimeout, int receiveTimeout) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeout);
            s.setSoTimeout(receiveTimeout);
            s.setTcpNoDelay(true);
            if (https) {
                SSLSocketFactory factory = sslSocketFactory != null
                    ? sslSocketFactory : (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) factory.createSocket(s, host, port, true);
                if (verifyHostname) {
                    SSLParameters params = ssl.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(params);
                }
                ssl.startHandshake();
                s = ssl;
            }
            socket = s;
            in = s.getInputStream();
            out = s.getOutputStream();
            exchangesOnConnection = 0;
            LOG.debug("Kerberos session connected to {}:{} (tls={})", host, port, https);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * Runs the SPNEGO handshake on this connection: POST with an empty body and
     * {@code Authorization: Negotiate <token>}, feeding each {@code WWW-Authenticate}
     * token back into the context until it is established. HTTP.sys binds the resulting
     * Kerberos session to this TCP connection.
     */
    private void handshake(int receiveTimeout) throws IOException {
        try {
            gss.newHandshake();
            byte[] token = gss.nextToken(null);
            int legs = 0;

            while (token != null) {
                if (++legs > MAX_HANDSHAKE_LEGS) {
                    throw new IOException("Kerberos Negotiate handshake did not converge after "
                        + MAX_HANDSHAKE_LEGS + " legs");
                }
                LOG.debug("Kerberos handshake leg {}: sending {} byte token", legs, token.length);

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", "application/soap+xml;charset=UTF-8");
                headers.put("Content-Length", "0");
                headers.put("Authorization", "Negotiate " + Base64.getEncoder().encodeToString(token));

                socket.setSoTimeout(receiveTimeout);
                writeRequest(out, headers, new byte[0]);
                RawResponse response = readResponse(in);
                if (response.connectionClose) {
                    throw new IOException("Server closed the connection during the Negotiate handshake"
                        + " (HTTP " + response.statusCode + "); the session binding cannot survive");
                }

                byte[] serverToken = extractNegotiateToken(response.headers);
                if (serverToken == null) {
                    if (gss.isEstablished()) {
                        break;
                    }
                    throw new IOException("Negotiate handshake failed: HTTP " + response.statusCode
                        + " with no WWW-Authenticate token and context not established");
                }
                token = gss.nextToken(serverToken);
            }

            if (!gss.isEstablished()) {
                throw new IOException("Negotiate handshake ended without an established GSS context");
            }
            gss.verifyNegotiatedProtections();
            LOG.debug("Kerberos session established on connection to {}:{}", host, port);
        } catch (GSSException e) {
            throw new IOException("Kerberos Negotiate handshake failed", e);
        } catch (javax.security.auth.login.LoginException e) {
            throw new IOException("Kerberos login failed", e);
        }
    }

    private static byte[] extractNegotiateToken(Map<String, List<String>> headers) {
        List<String> values = headers.get("WWW-Authenticate");
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && v.startsWith("Negotiate ")) {
                String b64 = v.substring("Negotiate ".length()).trim();
                if (!b64.isEmpty()) {
                    return Base64.getDecoder().decode(b64);
                }
            }
        }
        return null;
    }

    private void invalidateConnection() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        socket = null;
        in = null;
        out = null;
        exchangesOnConnection = 0;
    }

    /** Closes the connection and disposes the GSS context and JAAS login. */
    @Override
    public synchronized void close() {
        invalidateConnection();
        gss.close();
    }

    // ------------------------------------------------------------------------------
    // Minimal HTTP/1.1 codec. Static and package-visible for unit testing.
    // ------------------------------------------------------------------------------

    private void writeRequest(OutputStream os, Map<String, String> headers, byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("POST ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(':').append(port).append("\r\n");
        sb.append("User-Agent: OpenNMS WS-Man Client\r\n");
        sb.append("Connection: Keep-Alive\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        os.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) {
            os.write(body);
        }
        os.flush();
    }

    static final class RawResponse {
        int statusCode;
        String reasonPhrase = "";
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byte[] body = new byte[0];
        boolean connectionClose;
    }

    static RawResponse readResponse(InputStream is) throws IOException {
        RawResponse r = new RawResponse();

        String statusLine = readLine(is);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new EOFException("Connection closed before an HTTP status line was received");
        }
        // "HTTP/1.1 200 OK" — the reason phrase is optional
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("Malformed HTTP status line: " + statusLine);
        }
        try {
            r.statusCode = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed HTTP status code in: " + statusLine);
        }
        r.reasonPhrase = parts.length > 2 ? parts[2] : "";
        boolean http10 = statusLine.startsWith("HTTP/1.0");

        String line;
        while ((line = readLine(is)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            List<String> values = r.headers.get(name);
            if (values == null) {
                values = new java.util.ArrayList<>(1);
                r.headers.put(name, values);
            }
            values.add(value);
        }

        String connection = firstHeader(r.headers, "Connection");
        r.connectionClose = http10
            ? (connection == null || !"keep-alive".equalsIgnoreCase(connection))
            : "close".equalsIgnoreCase(connection);

        String transferEncoding = firstHeader(r.headers, "Transfer-Encoding");
        String contentLength = firstHeader(r.headers, "Content-Length");

        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            r.body = readChunkedBody(is);
        } else if (contentLength != null) {
            long len;
            try {
                len = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Malformed Content-Length: " + contentLength);
            }
            if (len < 0 || len > MAX_RESPONSE_BODY) {
                throw new IOException("Unreasonable Content-Length: " + len);
            }
            r.body = readFully(is, (int) len);
        } else if (r.connectionClose) {
            r.body = readToEof(is);
        } else {
            // Keep-alive with neither framing header: no body (e.g. a bare 401 challenge).
            r.body = new byte[0];
        }
        return r;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /** Reads one CRLF-terminated line as ISO-8859-1, without the terminator.
     *  Returns null on EOF before any byte was read. */
    private static String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int c = is.read();
        if (c < 0) {
            return null;
        }
        while (c >= 0 && c != '\n') {
            buf.write(c);
            c = is.read();
        }
        byte[] bytes = buf.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
    }

    private static byte[] readFully(InputStream is, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = is.read(buf, off, len - off);
            if (n < 0) {
                throw new EOFException("Connection closed after " + off + " of " + len + " body bytes");
            }
            off += n;
        }
        return buf;
    }

    private static byte[] readChunkedBody(InputStream is) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(is);
            if (sizeLine == null) {
                throw new EOFException("Connection closed inside a chunked body");
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Malformed chunk size: " + sizeLine);
            }
            if (size < 0 || body.size() + size > MAX_RESPONSE_BODY) {
                throw new IOException("Unreasonable chunk size: " + size);
            }
            if (size == 0) {
                // consume any trailers up to the blank line
                String line;
                while ((line = readLine(is)) != null && !line.isEmpty()) {
                    // ignore trailers
                }
                break;
            }
            body.write(readFully(is, size));
            String crlf = readLine(is);
            if (crlf == null || !crlf.isEmpty()) {
                throw new IOException("Missing CRLF after chunk data");
            }
        }
        return body.toByteArray();
    }

    private static byte[] readToEof(InputStream is) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) >= 0) {
            if (body.size() + n > MAX_RESPONSE_BODY) {
                throw new IOException("Response body exceeds " + MAX_RESPONSE_BODY + " bytes");
            }
            body.write(buf, 0, n);
        }
        return body.toByteArray();
    }

    /** Marker distinguishing "keep-alive connection died under us" from other I/O failures. */
    private static final class StaleConnectionException extends IOException {
        private static final long serialVersionUID = 1L;
        StaleConnectionException(IOException cause) {
            super(cause);
        }
    }
}

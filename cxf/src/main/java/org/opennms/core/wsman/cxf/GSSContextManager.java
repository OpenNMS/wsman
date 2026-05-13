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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.MessageProp;
import org.ietf.jgss.Oid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a Kerberos GSS-API security context for a single WS-Man endpoint and provides
 * the wrap/unwrap operations used by the Kerberos encryption interceptors to implement
 * MS-WSMV §2.2.9.1 KerberosEncryptedMessage.
 */
public class GSSContextManager {
    private static final Logger LOG = LoggerFactory.getLogger(GSSContextManager.class);

    private static final Oid KERBEROS_OID;
    private static final Oid SPNEGO_OID;
    static {
        try {
            KERBEROS_OID = new Oid("1.2.840.113554.1.2.2");
            SPNEGO_OID   = new Oid("1.3.6.1.5.5.2");
        } catch (GSSException e) {
            throw new RuntimeException("Failed to create GSS OIDs", e);
        }
    }

    // SSPI gss_wrap_iov "security trailer" size for AES256-CTS-HMAC-SHA1-96 (etype 18):
    //   16 (RFC 4121 wrap-token header) + 16 (encrypted confounder)
    //   + 16 (encrypted trailing header replica) + 12 (HMAC-SHA1-96) = 60 bytes.
    private static final int SSPI_HEADER_LEN_AES256 = 60;

    // RFC 4121 right-rotation count Microsoft uses for AES256: rotate the data portion by
    // 28 bytes (= 16 enc_header_copy + 12 HMAC) so the trailer sits at the front of the
    // data and the plaintext ciphertext at the back. RRC is written network byte order
    // (big-endian) per RFC 4121, which matches what Microsoft sends back in responses.
    private static final int WINRM_RRC = 28;

    private final String host;
    private final String username;
    private final String password;
    private final SSLSocketFactory sslSocketFactory;

    private GSSContext context;
    private Subject subject;

    /**
     * @param sslSocketFactory the {@link SSLSocketFactory} the HTTPS pre-flight should use,
     *   or {@code null} to use the JVM default. When the WS-Man endpoint runs over HTTPS
     *   with permissive cert validation, this MUST be the same instance CXF's
     *   {@code TLSClientParameters} is using — the JVM's HTTPS keep-alive cache keys
     *   connections by SSLSocketFactory identity, so a different instance would force CXF
     *   to open a fresh (unauthenticated) TCP connection for the encrypted body.
     */
    public GSSContextManager(String host, String username, String password,
                             SSLSocketFactory sslSocketFactory) {
        this.host = host;
        this.username = username;
        this.password = password;
        this.sslSocketFactory = sslSocketFactory;
    }

    /**
     * Returns the outbound Kerberos token (raw bytes, not Base64-encoded) to place
     * in the {@code Authorization: Negotiate} header, or {@code null} if the context
     * is already established and no further token is required.
     *
     * Pass {@code null} on the first call. Pass the server's decoded token bytes
     * (from {@code WWW-Authenticate: Negotiate}) if the server sends one back.
     */
    public synchronized byte[] getOutboundToken(byte[] incomingServerToken) throws GSSException, LoginException {
        ensureContext();
        if (context.isEstablished()) {
            return null;
        }
        byte[] tokenIn = incomingServerToken != null ? incomingServerToken : new byte[0];
        try {
            byte[] token = initSecContext(tokenIn);
            return (token != null && token.length > 0) ? token : null;
        } catch (PrivilegedActionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof GSSException) throw (GSSException) cause;
            throw new RuntimeException("GSS initSecContext failed", cause);
        }
    }

    /** Encrypts {@code data} using GSS wrap with confidentiality enabled. */
    public synchronized byte[] wrap(byte[] data) throws GSSException {
        MessageProp prop = new MessageProp(0, true);
        if (subject != null) {
            try {
                final byte[] d = data;
                return Subject.doAs(subject, (PrivilegedExceptionAction<byte[]>) () ->
                    context.wrap(d, 0, d.length, prop));
            } catch (PrivilegedActionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof GSSException) throw (GSSException) cause;
                throw new RuntimeException("GSS wrap failed", cause);
            }
        }
        return context.wrap(data, 0, data.length, prop);
    }

    /** Decrypts {@code data} using GSS unwrap. */
    public synchronized byte[] unwrap(byte[] data) throws GSSException {
        MessageProp prop = new MessageProp(0, false);
        if (subject != null) {
            try {
                final byte[] d = data;
                return Subject.doAs(subject, (PrivilegedExceptionAction<byte[]>) () ->
                    context.unwrap(d, 0, d.length, prop));
            } catch (PrivilegedActionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof GSSException) throw (GSSException) cause;
                throw new RuntimeException("GSS unwrap failed", cause);
            }
        }
        return context.unwrap(data, 0, data.length, prop);
    }

    /**
     * Wraps {@code data} and emits the result in the WinRM/SSPI iov format per MS-WSMV §2.2.9.1:
     * {@code [4-byte LE security-trailer-length][security-trailer bytes][ciphertext bytes]}.
     *
     * Windows expects the {@code gss_wrap_iov} buffer layout: a "header" (SSPI security trailer)
     * containing the RFC 4121 wrap-token header, the encrypted confounder, the encrypted
     * trailing header replica, and the HMAC; followed by a "data" buffer holding just the
     * encrypted plaintext. Java's GSS API only produces the monolithic RFC 4121 wrap token
     * (header || enc(confounder || plaintext || header) || HMAC), so this method splits that
     * token at the known offsets for AES256-CTS-HMAC-SHA1-96 and reassembles it.
     */
    public synchronized byte[] wrapWinRM(byte[] data) throws GSSException {
        byte[] wrapToken = wrap(data);
        return toWinRMFormat(wrapToken, data.length);
    }

    /**
     * Inverse of {@link #wrapWinRM}: takes the wire-format
     * {@code [4B LE sig-len][sig][ciphertext]} produced by an SSPI {@code gss_wrap_iov}
     * sender (Windows WinRM) and re-interleaves the bytes back into the monolithic RFC 4121
     * layout that Java GSS {@code unwrap()} expects.
     */
    public synchronized byte[] unwrapWinRM(byte[] winrmBytes) throws GSSException, IOException {
        return unwrap(fromWinRMFormat(winrmBytes));
    }

    public synchronized boolean isEstablished() {
        return context != null && context.isEstablished();
    }

    /**
     * Performs the Kerberos AP-REQ/AP-REP pre-flight against the WinRM endpoint.
     *
     * Windows HTTP.sys binds the Kerberos session to the TCP connection: once the
     * mutual-auth handshake completes on a bare POST, all subsequent encrypted bodies on
     * the same connection are decrypted with that session's key. The response body must
     * be fully drained (not just {@code getErrorStream()}) so the JVM returns the socket
     * to the keep-alive pool, and {@code disconnect()} must NOT be called — otherwise
     * CXF opens a fresh unauthenticated connection for the encrypted POST and gets 401.
     * Mirrors pywinrm's {@code setup_encryption()}.
     */
    public synchronized void performPreflightHandshake(String urlStr) throws GSSException, LoginException, IOException {
        ensureContext();
        if (context.isEstablished()) {
            return;
        }

        byte[] apReqToken = getOutboundToken(null);
        if (apReqToken == null) {
            return;
        }
        LOG.debug("Pre-flight: sending AP-REQ token ({} bytes) to {}", apReqToken.length, urlStr);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        // Use the caller-supplied SSLSocketFactory (typically the same permissive one CXF's
        // TLSClientParameters has, for strictSSL=false setups). This is required so the JVM
        // HTTPS keep-alive cache, which keys on SSLSocketFactory identity, can reuse this
        // pre-flight socket for CXF's encrypted POST. Hostname verification is also relaxed
        // when a non-default factory is in use, mirroring CXF's disableCNCheck behavior.
        if (sslSocketFactory != null && conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setSSLSocketFactory(sslSocketFactory);
            https.setHostnameVerifier((hostname, session) -> true);
        }
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Authorization", "Negotiate " + Base64.getEncoder().encodeToString(apReqToken));
        conn.setRequestProperty("Content-Type", "application/soap+xml;charset=UTF-8");
        conn.setRequestProperty("Content-Length", "0");
        conn.getOutputStream().close();

        int responseCode = conn.getResponseCode();
        String wwwAuth = conn.getHeaderField("WWW-Authenticate");

        try {
            InputStream body = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (body != null) {
                byte[] buf = new byte[4096];
                while (body.read(buf) >= 0) { /* drain */ }
                body.close();
            }
        } catch (IOException ignored) {}

        if (wwwAuth != null && wwwAuth.startsWith("Negotiate ")) {
            byte[] serverToken = Base64.getDecoder().decode(wwwAuth.substring("Negotiate ".length()).trim());
            getOutboundToken(serverToken);
            LOG.debug("Pre-flight: GSS context established={}", context.isEstablished());
        } else {
            LOG.warn("Pre-flight: no Negotiate token in response (code={}, WWW-Authenticate={})", responseCode, wwwAuth);
        }
    }

    /**
     * Splits Java's monolithic RFC 4121 wrap token into the SSPI gss_wrap_iov wire layout.
     *
     * <p>Java's {@code wrap()} produces
     * {@code [header(16) RRC=0 || enc_confounder(16) || enc_plaintext(N) || enc_header_copy(16) || HMAC(12)]}.
     * SSPI's {@code EncryptMessage} emits the same bytes right-rotated by 28 in the data portion,
     * with RRC=28 in the header — yielding
     * {@code [header(16) RRC=28 || enc_header_copy(16) || HMAC(12) || enc_confounder(16) || enc_plaintext(N)]}.
     * The WinRM length prefix is the SSPI security-trailer length (60 bytes for AES256),
     * so the wire is {@code [4B LE 60][60B signature][N ciphertext]}.
     */
    private static byte[] toWinRMFormat(byte[] wrapToken, int plaintextLen) throws GSSException {
        int expected = plaintextLen + SSPI_HEADER_LEN_AES256;
        if (wrapToken.length != expected) {
            throw new GSSException(GSSException.DEFECTIVE_TOKEN, -1,
                "Unexpected wrap-token length " + wrapToken.length + " for plaintext " + plaintextLen
                + " (expected " + expected + ", AES256-CTS-HMAC-SHA1-96 assumed)");
        }
        int dataLen = wrapToken.length - 16;
        byte[] out = new byte[4 + wrapToken.length];
        out[0] = (byte) SSPI_HEADER_LEN_AES256;
        out[1] = 0;
        out[2] = 0;
        out[3] = 0;
        System.arraycopy(wrapToken, 0, out, 4, 16);
        // RRC field, big-endian per RFC 4121 network byte order
        out[4 + 6] = 0;
        out[4 + 7] = (byte) WINRM_RRC;
        // right-rotate data by WINRM_RRC: last WINRM_RRC bytes (enc_header_copy + HMAC) go to front
        System.arraycopy(wrapToken, 16 + dataLen - WINRM_RRC, out, 4 + 16, WINRM_RRC);
        System.arraycopy(wrapToken, 16, out, 4 + 16 + WINRM_RRC, dataLen - WINRM_RRC);
        return out;
    }

    /**
     * Strips the WinRM length prefix, left-rotates the data portion by RRC (read big-endian
     * per RFC 4121) to recover Java's expected layout, and zeros the RRC field so Java's
     * {@code unwrap()} doesn't try to rotate again.
     */
    private static byte[] fromWinRMFormat(byte[] winrmBytes) throws IOException {
        if (winrmBytes.length < 4) {
            throw new IOException("WinRM encrypted section too short: " + winrmBytes.length);
        }
        int sigLen = (winrmBytes[0] & 0xff)
                   | ((winrmBytes[1] & 0xff) << 8)
                   | ((winrmBytes[2] & 0xff) << 16)
                   | ((winrmBytes[3] & 0xff) << 24);
        if (sigLen != SSPI_HEADER_LEN_AES256) {
            throw new IOException("Unexpected SSPI security-trailer length: " + sigLen
                + " (only AES256-CTS-HMAC-SHA1-96 is supported)");
        }
        if (winrmBytes.length < 4 + sigLen) {
            throw new IOException("WinRM encrypted section truncated (sigLen=" + sigLen
                + ", total=" + winrmBytes.length + ")");
        }
        byte[] wrapToken = new byte[winrmBytes.length - 4];
        System.arraycopy(winrmBytes, 4, wrapToken, 0, 16);
        int rrc = ((wrapToken[6] & 0xff) << 8) | (wrapToken[7] & 0xff);
        wrapToken[6] = 0;
        wrapToken[7] = 0;
        int dataLen = wrapToken.length - 16;
        int shift = rrc % dataLen;
        if (shift > 0) {
            System.arraycopy(winrmBytes, 4 + 16 + shift, wrapToken, 16, dataLen - shift);
            System.arraycopy(winrmBytes, 4 + 16, wrapToken, 16 + dataLen - shift, shift);
        } else {
            System.arraycopy(winrmBytes, 4 + 16, wrapToken, 16, dataLen);
        }
        return wrapToken;
    }

    private void ensureContext() throws GSSException, LoginException {
        if (context != null && context.isEstablished() && context.getLifetime() > 60) {
            return;
        }
        if (context != null && !context.isEstablished()) {
            // Handshake in progress — preserve the context so the pre-flight can advance it.
            return;
        }
        if (context != null) {
            try { context.dispose(); } catch (GSSException ignored) {}
            context = null;
        }

        if (username != null && password != null) {
            subject = acquireSubjectWithPassword();
        } else {
            // Mirror the -gssAuth convention: use the "WSManClient" JAAS login context,
            // which is read from java.security.auth.login.config. This is the same name
            // CXF's SpnegoAuthSupplier uses when setAuthorization("WSManClient") is called.
            subject = acquireSubjectFromLoginConfig("WSManClient");
        }

        GSSManager manager = GSSManager.getInstance();
        // SPNEGO OID so initSecContext produces a NegTokenInit wrapping the Kerberos AP-REQ
        // — what WinRM HTTP.sys expects. wrap()/unwrap() still produce raw RFC 4121 wrap
        // tokens regardless of the outer OID.
        GSSName serverName = manager.createName("http@" + host, GSSName.NT_HOSTBASED_SERVICE, KERBEROS_OID);

        if (subject != null) {
            try {
                final GSSManager mgr = manager;
                final GSSName srv = serverName;
                context = Subject.doAs(subject, (PrivilegedExceptionAction<GSSContext>) () -> {
                    GSSContext ctx = mgr.createContext(srv, SPNEGO_OID, null, GSSContext.DEFAULT_LIFETIME);
                    // Windows HTTP.sys requires mutual auth to complete the AP-REQ/AP-REP
                    // pre-flight before it will accept encrypted message bodies.
                    ctx.requestMutualAuth(true);
                    ctx.requestConf(true);
                    ctx.requestInteg(true);
                    return ctx;
                });
            } catch (PrivilegedActionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof GSSException) throw (GSSException) cause;
                throw new RuntimeException("Failed to create GSS context", cause);
            }
        } else {
            context = manager.createContext(serverName, SPNEGO_OID, null, GSSContext.DEFAULT_LIFETIME);
            context.requestMutualAuth(true);
            context.requestConf(true);
            context.requestInteg(true);
        }
    }

    private byte[] initSecContext(byte[] tokenIn) throws GSSException, PrivilegedActionException {
        if (subject != null) {
            final byte[] t = tokenIn;
            return Subject.doAs(subject, (PrivilegedExceptionAction<byte[]>) () ->
                context.initSecContext(t, 0, t.length));
        }
        return context.initSecContext(tokenIn, 0, tokenIn.length);
    }

    private static Subject acquireSubjectFromLoginConfig(String loginContextName) throws LoginException {
        LoginContext lc = new LoginContext(loginContextName);
        lc.login();
        return lc.getSubject();
    }

    private Subject acquireSubjectWithPassword() throws LoginException {
        Subject sub = new Subject();
        final String user = username;
        final char[] pass = password.toCharArray();
        LoginContext lc = new LoginContext("", sub,
            callbacks -> {
                for (Callback cb : callbacks) {
                    if (cb instanceof NameCallback) {
                        ((NameCallback) cb).setName(user);
                    } else if (cb instanceof PasswordCallback) {
                        ((PasswordCallback) cb).setPassword(pass);
                    }
                }
            },
            new Configuration() {
                @Override
                public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                    Map<String, Object> opts = new HashMap<>();
                    opts.put("useKeyTab", "false");
                    opts.put("doNotPrompt", "false");
                    opts.put("isInitiator", "true");
                    opts.put("principal", user);
                    return new AppConfigurationEntry[]{
                        new AppConfigurationEntry(
                            "com.sun.security.auth.module.Krb5LoginModule",
                            LoginModuleControlFlag.REQUIRED,
                            opts)
                    };
                }
            });
        lc.login();
        return lc.getSubject();
    }
}
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
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.io.CachedOutputStreamCallback;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encrypts outbound SOAP messages per MS-WSMV §2.2.9.1 KerberosEncryptedMessage.
 *
 * Runs in PRE_STREAM so the OutputStream is swapped before the SOAP serialisation chain
 * writes any bytes. When the cached stream is closed, the buffered UTF-8 SOAP body is GSS-
 * wrapped and emitted as a {@code multipart/encrypted} HTTP body in the SSPI gss_wrap_iov
 * wire format ({@code [4B LE 60][60B security trailer][N ciphertext]}). No per-request
 * Authorization header is sent: Windows binds the Kerberos session to the TCP connection
 * during the pre-flight handshake and reads it from there for every subsequent body on the
 * same keep-alive connection.
 */
public class KerberosEncryptOutInterceptor extends AbstractPhaseInterceptor<Message> {
    private static final Logger LOG = LoggerFactory.getLogger(KerberosEncryptOutInterceptor.class);

    static final String MULTIPART_CONTENT_TYPE =
        "multipart/encrypted;" +
        "protocol=\"application/HTTP-SPNEGO-session-encrypted\";" +
        "boundary=\"Encrypted Boundary\"";

    private final GSSContextManager gssManager;

    public KerberosEncryptOutInterceptor(GSSContextManager gssManager) {
        super(Phase.PRE_STREAM);
        this.gssManager = gssManager;
    }

    @Override
    public void handleMessage(Message message) throws Fault {
        OutputStream originalOs = message.getContent(OutputStream.class);
        if (originalOs == null) {
            return;
        }
        CachedOutputStream cache = new CachedOutputStream();
        cache.registerCallback(new EncryptCallback(originalOs, message));
        message.setContent(OutputStream.class, cache);
    }

    private class EncryptCallback implements CachedOutputStreamCallback {
        private final OutputStream target;
        private final Message message;

        EncryptCallback(OutputStream target, Message message) {
            this.target = target;
            this.message = message;
        }

        @Override
        public void onFlush(CachedOutputStream cos) {}

        @Override
        public void onClose(CachedOutputStream cos) {
            try {
                byte[] soapUtf8 = cos.getBytes();

                // Pre-flight must happen here, not at proxy-creation time, because
                // URLConnectionHTTPConduit opens the TCP connection lazily on the first
                // write. Doing it now puts the pre-flight socket in the JVM keep-alive
                // pool at the exact moment CXF calls connect(), so the encrypted body
                // rides the same Kerberos-session-bound connection.
                if (!gssManager.isEstablished()) {
                    String endpointUrl = (String) message.get(Message.ENDPOINT_ADDRESS);
                    try {
                        gssManager.performPreflightHandshake(endpointUrl);
                    } catch (Exception e) {
                        throw new Fault(new RuntimeException("Kerberos pre-flight handshake failed", e));
                    }
                }

                byte[] encryptedSection = gssManager.wrapWinRM(soapUtf8);
                byte[] multipart = buildMultipartBody(encryptedSection, soapUtf8.length);

                setHeader(message, "Content-Type", MULTIPART_CONTENT_TYPE);
                // CXF's HTTP conduit reads Message.CONTENT_TYPE independently of
                // PROTOCOL_HEADERS in some code paths; set both.
                message.put(Message.CONTENT_TYPE, MULTIPART_CONTENT_TYPE);
                // Headers.determineContentType() appends "; charset=<Message.ENCODING>" to
                // any Content-Type lacking "charset=" unless it's multipart/related. Our
                // multipart/encrypted would get the charset tacked on, which Windows
                // HTTP.sys rejects with 401. Clear the encoding so CXF has nothing to append.
                message.put(Message.ENCODING, null);
                setHeader(message, "Content-Length", String.valueOf(multipart.length));
                removeHeader(message, "Authorization");
                removeHeader(message, "Transfer-Encoding");

                LOG.debug("Kerberos-encrypted outbound message: SOAP {} bytes UTF-8, multipart {} bytes",
                    soapUtf8.length, multipart.length);

                target.write(multipart);
                target.flush();
                target.close();
            } catch (Exception e) {
                throw new Fault(e);
            }
        }
    }

    private static byte[] buildMultipartBody(byte[] encryptedSection, int originalSoapLength) throws IOException {
        // OriginalContent Length is the plaintext SOAP byte count, which WinRM validates
        // against the decrypted size. No CRLF between the binary section and the closing
        // boundary — matches pywinrm's wire format.
        String header =
            "--Encrypted Boundary\r\n" +
            "\tContent-Type: application/HTTP-SPNEGO-session-encrypted\r\n" +
            "\tOriginalContent: type=application/soap+xml;charset=UTF-8;Length=" + originalSoapLength + "\r\n" +
            "--Encrypted Boundary\r\n" +
            "\tContent-Type: application/octet-stream\r\n";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(header.getBytes(StandardCharsets.US_ASCII));
        baos.write(encryptedSection);
        baos.write("--Encrypted Boundary--\r\n".getBytes(StandardCharsets.US_ASCII));
        return baos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void setHeader(Message message, String name, String value) {
        Map<String, List<String>> headers = (Map<String, List<String>>) message.get(Message.PROTOCOL_HEADERS);
        if (headers == null) {
            headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            message.put(Message.PROTOCOL_HEADERS, headers);
        }
        headers.put(name, Collections.singletonList(value));
    }

    @SuppressWarnings("unchecked")
    private static void removeHeader(Message message, String name) {
        Map<String, List<String>> headers = (Map<String, List<String>>) message.get(Message.PROTOCOL_HEADERS);
        if (headers != null) {
            headers.remove(name);
        }
    }
}
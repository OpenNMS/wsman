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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decrypts inbound multipart/encrypted SOAP responses per MS-WSMV §2.2.9.1.
 *
 * Runs in RECEIVE (before LoggingInInterceptor and ReadHeadersInterceptor).
 * Encrypted response detection is done by inspecting the InputStream body
 * prefix ("--Encrypted Boundary") rather than the Content-Type header,
 * because WinRM servers do not consistently send a Content-Type on encrypted
 * responses.  If the body is not an encrypted multipart the stream is
 * restored and the message passes through unchanged.
 */
public class KerberosDecryptInInterceptor extends AbstractPhaseInterceptor<Message> {
    private static final Logger LOG = LoggerFactory.getLogger(KerberosDecryptInInterceptor.class);

    private static final byte[] BOUNDARY = "--Encrypted Boundary".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private final GSSContextManager gssManager;

    public KerberosDecryptInInterceptor(GSSContextManager gssManager) {
        super(Phase.RECEIVE);
        // Run before the LoggingInInterceptor so that interceptor logs decrypted SOAP.
        addBefore(LoggingInInterceptor.class.getName());
        this.gssManager = gssManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handleMessage(Message message) throws Fault {
        InputStream is = message.getContent(InputStream.class);
        if (is == null) {
            return;
        }

        byte[] body;
        try {
            body = IOUtils.readBytesFromStream(is);
        } catch (IOException e) {
            throw new Fault(e);
        }

        // Always restore so that downstream interceptors (or fault handlers) can read the body.
        message.setContent(InputStream.class, new ByteArrayInputStream(body));

        // Detect encrypted format by body content, not Content-Type (WinRM does not
        // reliably send Content-Type on Kerberos-encrypted responses).
        if (!startsWithBoundary(body)) {
            return;
        }

        try {
            // Process any server SPNEGO token in the response headers
            Map<String, List<String>> headers = (Map<String, List<String>>) message.get(Message.PROTOCOL_HEADERS);
            if (headers != null) {
                List<String> authHdrs = headers.get("WWW-Authenticate");
                if (authHdrs == null) authHdrs = headers.get("www-authenticate");
                if (authHdrs != null) {
                    for (String h : authHdrs) {
                        if (h.startsWith("Negotiate ")) {
                            byte[] serverToken = Base64.getDecoder().decode(
                                h.substring("Negotiate ".length()).trim());
                            try {
                                gssManager.getOutboundToken(serverToken);
                            } catch (Exception e) {
                                LOG.warn("Failed to process server Negotiate token for mutual auth", e);
                            }
                        }
                    }
                }
            }

            byte[] encrypted = parseEncryptedSection(body);
            byte[] soapUtf8 = gssManager.unwrapWinRM(encrypted);

            message.setContent(InputStream.class, new ByteArrayInputStream(soapUtf8));
            message.put(Message.CONTENT_TYPE, "application/soap+xml;charset=UTF-8");
            if (headers != null) {
                headers.put("Content-Type", Collections.singletonList("application/soap+xml;charset=UTF-8"));
            }

            LOG.debug("Kerberos-decrypted inbound message: {} encrypted bytes → {} SOAP UTF-8 bytes",
                encrypted.length, soapUtf8.length);

        } catch (Exception e) {
            // The raw body has already been restored above; throw so the fault chain can inspect it.
            LOG.warn("Kerberos decryption failed (body.length={})", body.length, e);
            throw new Fault(e);
        }
    }

    /** Returns true if the byte array starts with the MS-WSMV encrypted-boundary marker. */
    private static boolean startsWithBoundary(byte[] body) {
        if (body.length < BOUNDARY.length) {
            return false;
        }
        // Skip any leading whitespace / CRLF that some servers prepend
        int start = 0;
        while (start < body.length && (body[start] == '\r' || body[start] == '\n' || body[start] == ' ')) {
            start++;
        }
        if (body.length - start < BOUNDARY.length) {
            return false;
        }
        for (int i = 0; i < BOUNDARY.length; i++) {
            if (body[start + i] != BOUNDARY[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Locates the encrypted bytes in the multipart body.
     *
     * Expected layout:
     * <pre>
     * --Encrypted Boundary\r\n
     * Content-Type: application/HTTP-SPNEGO-session-encrypted\r\n
     * OriginalContent: ...\r\n
     * --Encrypted Boundary\r\n
     * Content-Type: application/octet-stream\r\n
     * &lt;encrypted bytes&gt;\r\n
     * --Encrypted Boundary--\r\n
     * </pre>
     */
    private static byte[] parseEncryptedSection(byte[] body) throws IOException {
        int firstBoundary = indexOf(body, BOUNDARY, 0);
        if (firstBoundary < 0) throw new IOException("Encrypted boundary not found in response body");

        int secondBoundary = indexOf(body, BOUNDARY, firstBoundary + BOUNDARY.length);
        if (secondBoundary < 0) throw new IOException("Second encrypted boundary not found in response body");

        // Skip past the boundary line (\r\n after the boundary marker)
        int pos = secondBoundary + BOUNDARY.length;
        pos = skipCRLF(body, pos);

        // MS-WSMV §2.2.9.1 tab-indents every header line within a part (\tContent-Type: …).
        // The encrypted payload follows with no blank-line separator and does NOT start with \t,
        // so we stop as soon as we see a non-tab byte (or a CRLF we can't find the end of).
        while (pos < body.length && body[pos] == '\t') {
            int lineEnd = indexOf(body, CRLF, pos);
            if (lineEnd < 0) break;
            pos = lineEnd + CRLF.length;
        }

        // Everything from here to the closing boundary (minus trailing \r\n) is encrypted bytes
        byte[] closingBoundary = "--Encrypted Boundary--".getBytes(StandardCharsets.US_ASCII);
        int closingPos = indexOf(body, closingBoundary, pos);
        if (closingPos < 0) {
            closingPos = body.length;
        }

        int end = closingPos;
        if (end >= 2 && body[end - 2] == '\r' && body[end - 1] == '\n') {
            end -= 2;
        }

        if (end <= pos) {
            throw new IOException("Encrypted section appears to be empty (pos=" + pos + ", end=" + end + ")");
        }

        return Arrays.copyOfRange(body, pos, end);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int skipCRLF(byte[] buf, int pos) {
        if (pos < buf.length - 1 && buf[pos] == '\r' && buf[pos + 1] == '\n') {
            return pos + 2;
        }
        if (pos < buf.length && buf[pos] == '\n') {
            return pos + 1;
        }
        return pos;
    }
}

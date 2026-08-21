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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Builds and parses the {@code multipart/encrypted} HTTP body framing used by MS-WSMV
 * §2.2.9.1 KerberosEncryptedMessage. The encrypted payload itself (the SSPI
 * {@code [4B LE sig-len][sig][ciphertext]} section) is produced and consumed by
 * {@link GSSContextManager}; this class only handles the surrounding MIME-ish framing.
 */
final class WinRMEncryptedMultipart {

    static final String CONTENT_TYPE =
        "multipart/encrypted;" +
        "protocol=\"application/HTTP-SPNEGO-session-encrypted\";" +
        "boundary=\"Encrypted Boundary\"";

    private static final byte[] BOUNDARY = "--Encrypted Boundary".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CLOSING_BOUNDARY = "--Encrypted Boundary--".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private WinRMEncryptedMultipart() {}

    /**
     * Builds the multipart/encrypted body around an already-encrypted section.
     *
     * OriginalContent Length is the plaintext SOAP byte count, which WinRM validates
     * against the decrypted size. No CRLF between the binary section and the closing
     * boundary — matches pywinrm's wire format.
     */
    static byte[] build(byte[] encryptedSection, int originalSoapLength) throws IOException {
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

    /**
     * Returns true if the body starts with the MS-WSMV encrypted-boundary marker
     * (allowing leading whitespace/CRLF that some servers prepend). Detection is done
     * on body content rather than Content-Type because WinRM servers do not
     * consistently send a Content-Type on encrypted responses.
     */
    static boolean isEncrypted(byte[] body) {
        if (body == null || body.length < BOUNDARY.length) {
            return false;
        }
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
    static byte[] extractEncryptedSection(byte[] body) throws IOException {
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

        // The section is self-delimiting when the OriginalContent Length is available:
        // [4B LE sigLen][sigLen bytes][ciphertext], and AES256-CTS ciphertext is exactly
        // as long as the plaintext, so the section length is 4 + sigLen + Length. Prefer
        // that over scanning for the closing boundary — ciphertext is opaque bytes and can
        // legitimately end with \r\n, which a trim-the-trailing-CRLF heuristic would eat.
        int declaredLength = parseOriginalContentLength(body, firstBoundary, secondBoundary);
        if (declaredLength >= 0 && pos + 4 <= body.length) {
            int sigLen = (body[pos] & 0xff)
                       | ((body[pos + 1] & 0xff) << 8)
                       | ((body[pos + 2] & 0xff) << 16)
                       | ((body[pos + 3] & 0xff) << 24);
            if (sigLen > 0 && sigLen < 1024) {
                long end = (long) pos + 4 + sigLen + declaredLength;
                if (end <= body.length) {
                    return Arrays.copyOfRange(body, pos, (int) end);
                }
            }
        }

        // Fallback: everything from here to the closing boundary (minus trailing \r\n)
        int closingPos = indexOf(body, CLOSING_BOUNDARY, pos);
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

    /**
     * Parses the {@code Length=N} attribute of the {@code OriginalContent} header in the
     * first multipart part (the plaintext SOAP byte count). Returns -1 if absent or
     * unparseable, in which case the caller falls back to boundary scanning.
     */
    private static int parseOriginalContentLength(byte[] body, int firstBoundary, int secondBoundary) {
        String firstPart = new String(body, firstBoundary, secondBoundary - firstBoundary,
            StandardCharsets.US_ASCII);
        int idx = firstPart.indexOf("Length=");
        if (idx < 0) {
            return -1;
        }
        int start = idx + "Length=".length();
        int end = start;
        while (end < firstPart.length() && Character.isDigit(firstPart.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            return Integer.parseInt(firstPart.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
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

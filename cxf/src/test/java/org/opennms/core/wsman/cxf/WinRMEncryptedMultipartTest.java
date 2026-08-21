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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.Test;

public class WinRMEncryptedMultipartTest {

    @Test
    public void buildThenExtract_roundTripsTheEncryptedSection() throws IOException {
        byte[] encrypted = syntheticSection(517);
        byte[] body = WinRMEncryptedMultipart.build(encrypted, 517);

        assertTrue(WinRMEncryptedMultipart.isEncrypted(body));
        assertArrayEquals(encrypted, WinRMEncryptedMultipart.extractEncryptedSection(body));
    }

    @Test
    public void buildThenExtract_roundTripsCiphertextEndingInCRLF() throws IOException {
        // Ciphertext is opaque bytes and can legitimately end with \r\n. The parser must
        // not trim those as if they were multipart framing (the OriginalContent Length
        // makes the section self-delimiting).
        byte[] encrypted = syntheticSection(128);
        encrypted[encrypted.length - 2] = '\r';
        encrypted[encrypted.length - 1] = '\n';
        byte[] body = WinRMEncryptedMultipart.build(encrypted, 128);
        assertArrayEquals(encrypted, WinRMEncryptedMultipart.extractEncryptedSection(body));
    }

    @Test
    public void extract_withoutOriginalContentLength_fallsBackToBoundaryScan() throws IOException {
        byte[] encrypted = syntheticSection(64);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write((
            "--Encrypted Boundary\r\n" +
            "\tContent-Type: application/HTTP-SPNEGO-session-encrypted\r\n" +
            // no OriginalContent header at all
            "--Encrypted Boundary\r\n" +
            "\tContent-Type: application/octet-stream\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(encrypted);
        body.write("--Encrypted Boundary--\r\n".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(encrypted, WinRMEncryptedMultipart.extractEncryptedSection(body.toByteArray()));
    }

    @Test
    public void isEncrypted_withLeadingWhitespace_isDetected() throws IOException {
        byte[] inner = WinRMEncryptedMultipart.build(syntheticSection(16), 16);
        ByteArrayOutputStream padded = new ByteArrayOutputStream();
        padded.write("\r\n \r\n".getBytes(StandardCharsets.US_ASCII));
        padded.write(inner);
        assertTrue(WinRMEncryptedMultipart.isEncrypted(padded.toByteArray()));
    }

    @Test
    public void isEncrypted_onCleartextSoap_isFalse() {
        byte[] soap = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"/>"
            .getBytes(StandardCharsets.UTF_8);
        assertFalse(WinRMEncryptedMultipart.isEncrypted(soap));
    }

    @Test
    public void isEncrypted_onEmptyAndShortBodies_isFalse() {
        assertFalse(WinRMEncryptedMultipart.isEncrypted(new byte[0]));
        assertFalse(WinRMEncryptedMultipart.isEncrypted(null));
        assertFalse(WinRMEncryptedMultipart.isEncrypted("--Enc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void extract_withoutBoundary_throws() {
        try {
            WinRMEncryptedMultipart.extractEncryptedSection("no boundary here".getBytes(StandardCharsets.US_ASCII));
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void extract_withEmptyEncryptedSection_throws() {
        String body =
            "--Encrypted Boundary\r\n" +
            "\tContent-Type: application/HTTP-SPNEGO-session-encrypted\r\n" +
            "\tOriginalContent: type=application/soap+xml;charset=UTF-8;Length=0\r\n" +
            "--Encrypted Boundary\r\n" +
            "\tContent-Type: application/octet-stream\r\n" +
            "--Encrypted Boundary--\r\n";
        try {
            WinRMEncryptedMultipart.extractEncryptedSection(body.getBytes(StandardCharsets.US_ASCII));
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void extract_toleratesMissingClosingBoundary() throws IOException {
        // Some servers have been observed to omit the trailing boundary; the payload
        // then runs to end-of-body.
        byte[] encrypted = syntheticSection(64);
        byte[] full = WinRMEncryptedMultipart.build(encrypted, 64);
        // strip "--Encrypted Boundary--\r\n" (24 bytes)
        byte[] truncated = new byte[full.length - 24];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        assertArrayEquals(encrypted, WinRMEncryptedMultipart.extractEncryptedSection(truncated));
    }

    /**
     * Builds a structurally valid encrypted section for a given plaintext length:
     * {@code [4B LE 60][60B signature][N ciphertext]} filled with pseudo-random bytes,
     * mirroring what {@code GSSContextManager.wrapWinRM} produces for AES256.
     */
    private static byte[] syntheticSection(int plaintextLen) {
        byte[] b = new byte[4 + 60 + plaintextLen];
        new Random(42).nextBytes(b);
        b[0] = 60;
        b[1] = 0;
        b[2] = 0;
        b[3] = 0;
        // Avoid pathological collisions with the ASCII boundary marker
        for (int i = 4; i + 1 < b.length; i++) {
            if (b[i] == '-' && b[i + 1] == '-') {
                b[i + 1] = 'x';
            }
        }
        return b;
    }
}

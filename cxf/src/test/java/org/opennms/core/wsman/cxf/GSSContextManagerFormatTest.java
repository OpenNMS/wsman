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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Random;

import org.ietf.jgss.GSSException;
import org.junit.Test;

/**
 * Tests the RFC 4121 &lt;-&gt; SSPI gss_wrap_iov byte-layout conversion in
 * {@link GSSContextManager} with synthetic wrap tokens. The layouts are pure byte
 * shuffles, so no established GSS context is needed:
 *
 * Java layout:  [header(16) RRC=0][enc_confounder(16)][enc_plaintext(N)][enc_header_copy(16)][HMAC(12)]
 * Wire layout:  [4B LE 60][header(16) RRC=28][enc_header_copy(16)][HMAC(12)][enc_confounder(16)][enc_plaintext(N)]
 */
public class GSSContextManagerFormatTest {

    private static final int TRAILER = 60;   // SSPI security-trailer length for AES256
    private static final int RRC = 28;

    @Test
    public void toWinRMFormat_layout() throws GSSException {
        int plaintextLen = 100;
        byte[] token = syntheticWrapToken(plaintextLen);
        byte[] wire = GSSContextManager.toWinRMFormat(token, plaintextLen);

        assertEquals(4 + token.length, wire.length);

        // 4-byte little-endian trailer length
        assertEquals(TRAILER, wire[0] & 0xff);
        assertEquals(0, wire[1]);
        assertEquals(0, wire[2]);
        assertEquals(0, wire[3]);

        // header copied, with the RRC field (bytes 6-7 of the header) set to 28 big-endian
        for (int i = 0; i < 16; i++) {
            if (i == 6) {
                assertEquals(0, wire[4 + i]);
            } else if (i == 7) {
                assertEquals(RRC, wire[4 + i] & 0xff);
            } else {
                assertEquals("header byte " + i, token[i], wire[4 + i]);
            }
        }

        // data portion right-rotated by 28: the last 28 bytes of the Java data portion
        // (enc_header_copy + HMAC) lead, followed by confounder + ciphertext
        int dataLen = token.length - 16;
        for (int i = 0; i < RRC; i++) {
            assertEquals("rotated byte " + i, token[16 + dataLen - RRC + i], wire[4 + 16 + i]);
        }
        for (int i = 0; i < dataLen - RRC; i++) {
            assertEquals("body byte " + i, token[16 + i], wire[4 + 16 + RRC + i]);
        }
    }

    @Test
    public void fromWinRMFormat_isInverseOfToWinRMFormat() throws GSSException, IOException {
        for (int plaintextLen : new int[] {1, 28, 29, 100, 4096}) {
            byte[] token = syntheticWrapToken(plaintextLen);
            byte[] wire = GSSContextManager.toWinRMFormat(token, plaintextLen);
            byte[] recovered = GSSContextManager.fromWinRMFormat(wire);
            // fromWinRMFormat zeros the RRC field, which was already 0 in the synthetic
            // token, so the round trip must be exact.
            assertArrayEquals("plaintextLen=" + plaintextLen, token, recovered);
        }
    }

    @Test
    public void toWinRMFormat_wrongTokenLength_throws() {
        byte[] token = syntheticWrapToken(100);
        try {
            GSSContextManager.toWinRMFormat(token, 99); // claims a different plaintext size
            fail("expected GSSException");
        } catch (GSSException expected) {
            // expected: only AES256's fixed 60-byte overhead is supported
        }
    }

    @Test
    public void fromWinRMFormat_rejectsUnknownTrailerLength() {
        // A 16-byte trailer would be RC4/DES etypes, which we do not support
        byte[] wire = new byte[4 + 16 + 8];
        wire[0] = 16;
        try {
            GSSContextManager.fromWinRMFormat(wire);
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void fromWinRMFormat_rejectsTruncatedInput() {
        byte[] tooShort = new byte[] {60, 0, 0};
        try {
            GSSContextManager.fromWinRMFormat(tooShort);
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }

        byte[] truncated = new byte[4 + 30]; // claims 60-byte trailer, has 30 bytes
        truncated[0] = 60;
        try {
            GSSContextManager.fromWinRMFormat(truncated);
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    /**
     * Builds a synthetic Java-GSS-layout wrap token: 16-byte RFC 4121 header with RRC=0
     * followed by (16 confounder + N ciphertext + 16 header-copy + 12 HMAC) random bytes.
     */
    private static byte[] syntheticWrapToken(int plaintextLen) {
        byte[] token = new byte[plaintextLen + TRAILER];
        new Random(1234).nextBytes(token);
        // RFC 4121 wrap-token header: TOK_ID 05 04, flags, filler FF, EC, RRC=0, SND_SEQ
        token[0] = 0x05;
        token[1] = 0x04;
        token[2] = 0x06; // flags: sealed | acceptor-subkey
        token[3] = (byte) 0xFF;
        token[4] = 0x00; // EC hi
        token[5] = 0x00; // EC lo
        token[6] = 0x00; // RRC hi
        token[7] = 0x00; // RRC lo
        return token;
    }
}

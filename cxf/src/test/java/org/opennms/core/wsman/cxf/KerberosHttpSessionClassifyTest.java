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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.Test;
import org.opennms.core.wsman.cxf.KerberosHttpSession.RawResponse;
import org.opennms.core.wsman.cxf.KerberosHttpSession.Response;

/**
 * Tests the cleartext-response policy in {@link KerberosHttpSession#classify}: on an
 * established encrypted session a cleartext body is unauthenticated (an on-path attacker
 * can forge it), so it must never be handed to the SOAP chain. Only the cleartext
 * branches are exercised here; they never touch the GSS layer, so no established
 * context is needed.
 */
public class KerberosHttpSessionClassifyTest {

    private KerberosHttpSession session() throws Exception {
        return new KerberosHttpSession(new URL("http://windows-host:5985/wsman"),
            new GSSContextManager("windows-host", null, null), null, false);
    }

    private static RawResponse raw(int status, String reason, String body) {
        RawResponse r = new RawResponse();
        r.statusCode = status;
        r.reasonPhrase = reason;
        r.body = body.getBytes(StandardCharsets.UTF_8);
        return r;
    }

    @Test
    public void cleartext200_isRejectedAsDowngrade() throws Exception {
        try {
            session().classify(raw(200, "OK", "<s:Envelope>forged result</s:Envelope>"));
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("cleartext"));
            // the forged body must not leak into the message either
            assertFalse(e.getMessage(), e.getMessage().contains("forged result"));
        }
    }

    @Test
    public void cleartext401_passesStatusThroughWithBodyWithheld() throws Exception {
        RawResponse r = raw(401, "Unauthorized", "<html>attacker controlled junk</html>");
        r.headers.put("WWW-Authenticate", Collections.singletonList("Negotiate"));
        Response response = session().classify(r);
        assertEquals(401, response.getStatusCode());
        assertEquals("Unauthorized", response.getReasonPhrase());
        assertEquals("Negotiate", response.getHeaders().get("WWW-Authenticate").get(0));
        assertEquals("the unauthenticated body must be withheld", 0, response.getBody().length);
        assertFalse(response.isDecrypted());
    }

    @Test
    public void cleartext500_isRejectedWithSanitizedExcerpt() throws Exception {
        try {
            session().classify(raw(500, "Internal Server Error",
                "<s:Fault>\r\n\tWSManFault: cannot decrypt</s:Fault>"));
            fail("expected IOException");
        } catch (IOException e) {
            String msg = e.getMessage();
            assertTrue(msg, msg.contains("500"));
            // diagnostics are preserved, but control characters are not
            assertTrue(msg, msg.contains("WSManFault: cannot decrypt"));
            assertFalse(msg, msg.contains("\t"));
            assertFalse(msg, msg.contains("\r"));
        }
    }

    @Test
    public void sanitizeForDiagnostics_truncatesAndStripsControls() {
        assertEquals("(empty)", KerberosHttpSession.sanitizeForDiagnostics(new byte[0]));
        assertEquals("(no printable content)",
            KerberosHttpSession.sanitizeForDiagnostics(new byte[] {0, 1, 2, '\r', '\n'}));

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            big.append('a');
        }
        String sanitized = KerberosHttpSession.sanitizeForDiagnostics(
            big.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(303, sanitized.length()); // 300 chars + "..."
        assertTrue(sanitized.endsWith("..."));
    }
}

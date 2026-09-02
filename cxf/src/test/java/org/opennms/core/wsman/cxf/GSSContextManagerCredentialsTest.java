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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Date;

import javax.security.auth.DestroyFailedException;
import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.kerberos.KeyTab;

import org.junit.Test;

/**
 * Tests {@link GSSContextManager#hasUsableKerberosCredentials(Subject, long)}: the
 * TGT-expiry re-login decision. Long-lived busy clients must detect an expired (or
 * nearly expired) TGT and re-login, while keytab-backed subjects must NOT re-login,
 * since JGSS acquires fresh tickets from the keytab on its own.
 */
public class GSSContextManagerCredentialsTest {

    // Anchored to the real clock because KerberosTicket.isCurrent() consults it too:
    // a fully fake epoch would make "valid" test tickets read as expired in real time.
    private static final long NOW = System.currentTimeMillis();
    private static final long HOUR = 3_600_000L;

    @Test
    public void currentTgt_isUsable() {
        Subject subject = subjectWithTgt(NOW + 8 * HOUR);
        assertTrue(GSSContextManager.hasUsableKerberosCredentials(subject, NOW));
    }

    @Test
    public void expiredTgt_isNotUsable() {
        Subject subject = subjectWithTgt(NOW - HOUR);
        assertFalse(GSSContextManager.hasUsableKerberosCredentials(subject, NOW));
    }

    @Test
    public void tgtWithinExpiryMargin_isNotUsable() {
        // 30s of life left is inside the 60s margin: re-login proactively rather than
        // risk the ticket expiring between the check and the AP-REQ.
        Subject subject = subjectWithTgt(NOW + 30_000);
        assertFalse(GSSContextManager.hasUsableKerberosCredentials(subject, NOW));
    }

    @Test
    public void tgtJustPastExpiryMargin_isUsable() {
        Subject subject = subjectWithTgt(NOW + GSSContextManager.TGT_EXPIRY_MARGIN_MS + 1_000);
        assertTrue(GSSContextManager.hasUsableKerberosCredentials(subject, NOW));
    }

    @Test
    public void destroyedTgt_isNotUsable() throws DestroyFailedException {
        KerberosTicket ticket = tgt(NOW + 8 * HOUR);
        ticket.destroy();
        Subject subject = new Subject();
        subject.getPrivateCredentials().add(ticket);
        assertFalse(GSSContextManager.hasUsableKerberosCredentials(subject, NOW));
    }

    @Test
    public void serviceTicketAlone_isNotUsable() {
        // A leftover service ticket (non-krbtgt) cannot initiate a new handshake.
        Subject subject = new Subject();
        subject.getPrivateCredentials().add(ticket(
            "http/host.example.com@EXAMPLE.COM", NOW + 8 * HOUR));
        assertFalse(GSSContextManager.hasUsableKerberosCredentials(subject, NOW));
    }

    @Test
    public void keytabWithoutTicket_isUsable() {
        // JGSS acquires TGTs from a keytab on its own; no re-login needed, ever.
        Subject subject = new Subject();
        subject.getPrivateCredentials().add(KeyTab.getInstance(new File("/nonexistent.keytab")));
        assertTrue(GSSContextManager.hasUsableKerberosCredentials(subject, NOW));
    }

    @Test
    public void keytabWithExpiredTicket_isUsable() {
        Subject subject = subjectWithTgt(NOW - HOUR);
        subject.getPrivateCredentials().add(KeyTab.getInstance(new File("/nonexistent.keytab")));
        assertTrue(GSSContextManager.hasUsableKerberosCredentials(subject, NOW));
    }

    @Test
    public void emptySubject_isNotUsable() {
        assertFalse(GSSContextManager.hasUsableKerberosCredentials(new Subject(), NOW));
    }

    @Test
    public void nullSubject_isNotUsable() {
        assertFalse(GSSContextManager.hasUsableKerberosCredentials(null, NOW));
    }

    private static Subject subjectWithTgt(long endTimeMillis) {
        Subject subject = new Subject();
        subject.getPrivateCredentials().add(tgt(endTimeMillis));
        return subject;
    }

    private static KerberosTicket tgt(long endTimeMillis) {
        return ticket("krbtgt/EXAMPLE.COM@EXAMPLE.COM", endTimeMillis);
    }

    private static KerberosTicket ticket(String serverPrincipal, long endTimeMillis) {
        return new KerberosTicket(
            new byte[] {1},                                    // opaque ASN.1 encoding
            new KerberosPrincipal("user@EXAMPLE.COM"),
            new KerberosPrincipal(serverPrincipal),
            new byte[16], 18,                                  // AES256 session key
            new boolean[8],
            new Date(NOW - HOUR),                              // authTime
            null,                                              // startTime (defaults to authTime)
            new Date(endTimeMillis),
            null,                                              // renewTill
            null);                                             // client addresses
    }
}

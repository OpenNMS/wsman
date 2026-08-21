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

import java.io.Closeable;
import java.io.IOException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Map;

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
 * Manages the Kerberos GSS-API security material for a single WS-Man endpoint: the JAAS
 * {@link Subject} (acquired once and reused across connections, so reconnects need a new
 * AP-REQ but not a new AS-REQ), the per-connection {@link GSSContext}, and the
 * wrap/unwrap operations used to implement MS-WSMV §2.2.9.1 KerberosEncryptedMessage.
 *
 * <p>The HTTP transport (socket ownership, the Negotiate handshake exchange, and the
 * multipart/encrypted framing) lives in {@link KerberosHttpSession}; this class only
 * produces and consumes GSS tokens.
 *
 * <p>Because Windows HTTP.sys binds a Kerberos session to a single TCP connection, a
 * context established over one connection is useless on another. {@link #newHandshake()}
 * must be called each time the transport opens a fresh connection, and it disposes any
 * prior context.
 */
public class GSSContextManager implements Closeable {
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

    private LoginContext loginContext;
    private Subject subject;
    private boolean loggedIn;
    private GSSContext context;

    public GSSContextManager(String host, String username, String password) {
        this.host = host;
        this.username = username;
        this.password = password;
    }

    /**
     * Discards any existing context and creates a fresh one for a new TCP connection.
     * The JAAS login (and therefore the TGT) is reused across handshakes; only the
     * AP-REQ/AP-REP exchange is redone.
     */
    public synchronized void newHandshake() throws GSSException, LoginException {
        disposeContext();
        ensureLogin();

        GSSManager manager = GSSManager.getInstance();
        // SPNEGO OID so initSecContext produces a NegTokenInit wrapping the Kerberos AP-REQ
        // — what WinRM HTTP.sys expects. wrap()/unwrap() still produce raw RFC 4121 wrap
        // tokens regardless of the outer OID.
        final GSSName serverName = manager.createName("http@" + host, GSSName.NT_HOSTBASED_SERVICE, KERBEROS_OID);
        final GSSManager mgr = manager;

        PrivilegedExceptionAction<GSSContext> createContext = () -> {
            GSSContext ctx = mgr.createContext(serverName, SPNEGO_OID, null, GSSContext.DEFAULT_LIFETIME);
            // Windows HTTP.sys requires mutual auth to complete the AP-REQ/AP-REP
            // handshake before it will accept encrypted message bodies.
            ctx.requestMutualAuth(true);
            ctx.requestConf(true);
            ctx.requestInteg(true);
            // Defaults, but make the intent explicit: the per-message replay/reorder
            // flags checked in unwrap() depend on these being negotiated.
            ctx.requestReplayDet(true);
            ctx.requestSequenceDet(true);
            return ctx;
        };

        if (subject != null) {
            try {
                context = Subject.doAs(subject, createContext);
            } catch (PrivilegedActionException e) {
                throw unwrapGSS(e, "Failed to create GSS context");
            }
        } else {
            try {
                context = createContext.run();
            } catch (GSSException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to create GSS context", e);
            }
        }
    }

    /**
     * Advances the handshake: feeds the server's token (or an empty token for the initial
     * AP-REQ) into {@code initSecContext} and returns the next token to send, or
     * {@code null} when there is nothing further to send.
     */
    public synchronized byte[] nextToken(byte[] incomingServerToken) throws GSSException {
        if (context == null) {
            throw new IllegalStateException("newHandshake() must be called before nextToken()");
        }
        final byte[] tokenIn = incomingServerToken != null ? incomingServerToken : new byte[0];
        byte[] token;
        if (subject != null) {
            try {
                token = Subject.doAs(subject, (PrivilegedExceptionAction<byte[]>) () ->
                    context.initSecContext(tokenIn, 0, tokenIn.length));
            } catch (PrivilegedActionException e) {
                throw unwrapGSS(e, "GSS initSecContext failed");
            }
        } else {
            token = context.initSecContext(tokenIn, 0, tokenIn.length);
        }
        return (token != null && token.length > 0) ? token : null;
    }

    public synchronized boolean isEstablished() {
        return context != null && context.isEstablished();
    }

    /**
     * Verifies that the established context actually negotiated the protections we
     * requested. MS-WSMV §2.2.9.1 encryption is meaningless over a context that only
     * provides integrity (or neither), so a failed negotiation is a hard error, not
     * something to discover one message at a time.
     */
    public synchronized void verifyNegotiatedProtections() throws GSSException {
        if (context == null || !context.isEstablished()) {
            throw new GSSException(GSSException.NO_CONTEXT, -1, "GSS context is not established");
        }
        if (!context.getConfState()) {
            throw new GSSException(GSSException.UNAVAILABLE, -1,
                "Kerberos context did not negotiate confidentiality; refusing to send encrypted messages");
        }
        if (!context.getIntegState()) {
            throw new GSSException(GSSException.UNAVAILABLE, -1,
                "Kerberos context did not negotiate integrity; refusing to send encrypted messages");
        }
        if (!context.getMutualAuthState()) {
            throw new GSSException(GSSException.UNAVAILABLE, -1,
                "Kerberos mutual authentication did not complete; refusing to send encrypted messages");
        }
        if (!context.getReplayDetState()) {
            LOG.warn("Kerberos context did not negotiate replay detection; " +
                "per-message duplicate/old-token checks will not fire");
        }
    }

    /**
     * Encrypts {@code data} using GSS wrap and verifies that confidentiality was
     * actually applied to the token (an integrity-only wrap must not be sent as an
     * "encrypted" message).
     */
    public synchronized byte[] wrap(byte[] data) throws GSSException {
        MessageProp prop = new MessageProp(0, true);
        byte[] token = doWrap(data, prop);
        if (!prop.getPrivacy()) {
            throw new GSSException(GSSException.UNAVAILABLE, -1,
                "GSS wrap did not apply confidentiality; refusing to send the message as encrypted");
        }
        return token;
    }

    /**
     * Decrypts {@code data} using GSS unwrap and enforces the per-message security
     * state: the token must have been confidential (not integrity-only), and replayed
     * or expired tokens are rejected. Out-of-sequence and gap indications are logged
     * rather than fatal — HTTP request/response framing already orders messages, and
     * benign pipelining differences would otherwise break the session.
     */
    public synchronized byte[] unwrap(byte[] data) throws GSSException {
        MessageProp prop = new MessageProp(0, false);
        byte[] plaintext = doUnwrap(data, prop);
        if (!prop.getPrivacy()) {
            throw new GSSException(GSSException.BAD_MIC, -1,
                "Received a token without confidentiality on an encrypted session; rejecting");
        }
        if (prop.isDuplicateToken() || prop.isOldToken()) {
            throw new GSSException(GSSException.DUPLICATE_TOKEN, -1,
                "Received a duplicate or expired GSS token (possible replay); rejecting");
        }
        if (prop.isUnseqToken() || prop.isGapToken()) {
            LOG.warn("GSS unwrap reported an out-of-sequence or gap token (unseq={}, gap={})",
                prop.isUnseqToken(), prop.isGapToken());
        }
        return plaintext;
    }

    private byte[] doWrap(byte[] data, MessageProp prop) throws GSSException {
        if (subject != null) {
            try {
                final byte[] d = data;
                return Subject.doAs(subject, (PrivilegedExceptionAction<byte[]>) () ->
                    context.wrap(d, 0, d.length, prop));
            } catch (PrivilegedActionException e) {
                throw unwrapGSS(e, "GSS wrap failed");
            }
        }
        return context.wrap(data, 0, data.length, prop);
    }

    private byte[] doUnwrap(byte[] data, MessageProp prop) throws GSSException {
        if (subject != null) {
            try {
                final byte[] d = data;
                return Subject.doAs(subject, (PrivilegedExceptionAction<byte[]>) () ->
                    context.unwrap(d, 0, d.length, prop));
            } catch (PrivilegedActionException e) {
                throw unwrapGSS(e, "GSS unwrap failed");
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

    /**
     * Disposes the GSS context and logs out the JAAS session. Safe to call multiple times.
     */
    @Override
    public synchronized void close() {
        disposeContext();
        if (loginContext != null && loggedIn) {
            try {
                loginContext.logout();
            } catch (LoginException e) {
                LOG.debug("JAAS logout failed (ignoring)", e);
            }
        }
        loginContext = null;
        subject = null;
        loggedIn = false;
    }

    private void disposeContext() {
        if (context != null) {
            try {
                context.dispose();
            } catch (GSSException ignored) {}
            context = null;
        }
    }

    private void ensureLogin() throws LoginException {
        if (loggedIn) {
            return;
        }
        if (username != null && password != null) {
            loginContext = buildPasswordLoginContext();
        } else {
            // Mirror the -gssAuth convention: use the "WSManClient" JAAS login context,
            // which is read from java.security.auth.login.config. This is the same name
            // CXF's SpnegoAuthSupplier uses when setAuthorization("WSManClient") is called.
            loginContext = new LoginContext("WSManClient");
        }
        loginContext.login();
        subject = loginContext.getSubject();
        loggedIn = true;
    }

    private LoginContext buildPasswordLoginContext() throws LoginException {
        Subject sub = new Subject();
        final String user = username;
        final char[] pass = password.toCharArray();
        return new LoginContext("", sub,
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
    }

    private static GSSException unwrapGSS(PrivilegedActionException e, String message) {
        Throwable cause = e.getCause();
        if (cause instanceof GSSException) {
            return (GSSException) cause;
        }
        throw new RuntimeException(message, cause);
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
    static byte[] toWinRMFormat(byte[] wrapToken, int plaintextLen) throws GSSException {
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
    static byte[] fromWinRMFormat(byte[] winrmBytes) throws IOException {
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
}

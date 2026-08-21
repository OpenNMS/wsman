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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;

import org.apache.cxf.Bus;
import org.apache.cxf.message.Message;
import org.apache.cxf.service.model.EndpointInfo;
import org.apache.cxf.transport.http.Address;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transport.https.HttpsURLConnectionInfo;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.ws.addressing.EndpointReferenceType;

/**
 * A CXF {@link HTTPConduit} that sends every message through a
 * {@link KerberosHttpSession}, implementing MS-WSMV §2.2.9.1 Kerberos message
 * encryption over a connection the session owns.
 *
 * <p>The model is "buffer fully, exchange once": the outbound SOAP is collected in
 * memory (chunking is disabled on WS-Man clients anyway), encrypted, and exchanged on
 * the session's socket when CXF closes the output stream; the decrypted response is
 * handed back to the in-chain as a plain byte stream. Because the base class's
 * {@code doProcessResponseCode()} still sees the real HTTP status, 401s surface as
 * {@code org.apache.cxf.transport.http.HTTPException} (and therefore as
 * {@code UnauthorizedException} to callers), and encrypted 500 fault bodies are
 * decrypted and flow to the SOAP fault chain exactly as they do for the stock
 * conduits. Cleartext error bodies are unauthenticated and never reach the in-chain:
 * the session withholds a 401's body and fails hard on any other cleartext response
 * (see {@link KerberosHttpSession}).
 *
 * <p>Retransmit-related hooks are unsupported: redirects and auth-retry are handled
 * (where meaningful) inside the session, not by CXF's retransmit machinery.
 */
public class KerberosHttpConduit extends HTTPConduit {

    private final KerberosHttpSession session;

    public KerberosHttpConduit(Bus b, EndpointInfo ei, EndpointReferenceType t,
                               KerberosHttpSession session) throws IOException {
        super(b, ei, t);
        this.session = session;
    }

    @Override
    protected void setupConnection(Message message, Address address, HTTPClientPolicy csPolicy) {
        // Nothing to set up per-request: the session owns the (persistent) connection.
    }

    @Override
    protected OutputStream createOutputStream(Message message, boolean needToCacheRequest,
                                              boolean isChunking, int chunkThreshold) throws IOException {
        try {
            return new KerberosWrappedOutputStream(message);
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() {
        // Deliberately does NOT close the session: it is shared across the several
        // short-lived proxies/conduits a CXFWSManClient creates, and is closed by
        // CXFWSManClient.close().
        super.close();
    }

    class KerberosWrappedOutputStream extends WrappedOutputStream {
        private ByteArrayOutputStream requestBuffer;
        private KerberosHttpSession.Response response;

        KerberosWrappedOutputStream(Message message) throws URISyntaxException {
            // No caching-for-retransmission, no chunking: the buffer IS the message.
            super(message, false, false, 0, getConduitName(), getURI());
        }

        @Override
        protected void setupWrappedStream() {
            requestBuffer = new ByteArrayOutputStream(4096);
            wrappedStream = requestBuffer;
        }

        /** Performs the encrypted exchange exactly once, on first demand. */
        private KerberosHttpSession.Response exchange() throws IOException {
            if (response == null) {
                byte[] soapUtf8 = requestBuffer != null ? requestBuffer.toByteArray() : new byte[0];
                HTTPClientPolicy policy = getClient(outMessage);
                int connectTimeout = determineConnectionTimeout(outMessage, policy);
                int receiveTimeout = determineReceiveTimeout(outMessage, policy);
                response = session.sendEncrypted(soapUtf8, connectTimeout, receiveTimeout);
            }
            return response;
        }

        @Override
        protected int getResponseCode() throws IOException {
            return exchange().getStatusCode();
        }

        @Override
        protected String getResponseMessage() throws IOException {
            return exchange().getReasonPhrase();
        }

        @Override
        protected void updateResponseHeaders(Message inMessage) throws IOException {
            KerberosHttpSession.Response r = exchange();
            inMessage.put(Message.PROTOCOL_HEADERS, r.getHeaders());
            inMessage.put(Message.CONTENT_TYPE, r.getContentType());
        }

        @Override
        protected InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(exchange().getBody());
        }

        @Override
        protected InputStream getPartialResponse() {
            // Only meaningful for oneway/decoupled MEPs, which WS-Man does not use.
            return null;
        }

        @Override
        protected void closeInputStream() {
            // Byte-array backed; nothing to release.
        }

        @Override
        protected void handleResponseAsync() throws IOException {
            handleResponseOnWorkqueue(true, false);
        }

        @Override
        protected HttpsURLConnectionInfo getHttpsURLConnectionInfo() {
            // Only consulted when a MessageTrustDecider is registered, which this
            // conduit does not support (TLS policy is fixed at session construction).
            return null;
        }

        @Override
        protected void setProtocolHeaders() {
            // The session builds the wire headers itself (multipart Content-Type,
            // Content-Length, Host); CXF's protocol headers are intentionally not
            // copied onto an encrypted exchange.
        }

        @Override
        protected void setFixedLengthStreamingMode(int i) {
            // Not applicable: the request is fully buffered.
        }

        @Override
        protected boolean usingProxy() {
            return false;
        }

        @Override
        public void thresholdReached() {
            // Chunking is disabled; the buffer simply grows.
        }

        @Override
        protected void setupNewConnection(String newURL) throws IOException {
            throw new IOException("Redirects are not supported over a Kerberos-encrypted session"
                + " (requested: " + newURL + ")");
        }

        @Override
        protected void retransmitStream() throws IOException {
            throw new IOException("CXF-level retransmission is not supported over a"
                + " Kerberos-encrypted session");
        }

        @Override
        protected void updateCookiesBeforeRetransmit() {
            // Retransmits unsupported; nothing to do.
        }
    }
}

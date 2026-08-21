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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.opennms.core.wsman.cxf.KerberosHttpSession.RawResponse;

/**
 * Tests the minimal HTTP/1.1 response codec inside {@link KerberosHttpSession}.
 */
public class KerberosHttpSessionCodecTest {

    @Test
    public void contentLengthBody() throws IOException {
        RawResponse r = parse(
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/soap+xml;charset=UTF-8\r\n" +
            "Content-Length: 11\r\n" +
            "\r\n" +
            "hello world");
        assertEquals(200, r.statusCode);
        assertEquals("OK", r.reasonPhrase);
        assertEquals("application/soap+xml;charset=UTF-8", r.headers.get("Content-Type").get(0));
        assertArrayEquals("hello world".getBytes(StandardCharsets.US_ASCII), r.body);
        assertFalse(r.connectionClose);
    }

    @Test
    public void headerLookupIsCaseInsensitive() throws IOException {
        RawResponse r = parse(
            "HTTP/1.1 401 Unauthorized\r\n" +
            "www-authenticate: Negotiate\r\n" +
            "content-length: 0\r\n" +
            "\r\n");
        assertEquals(401, r.statusCode);
        assertEquals("Negotiate", r.headers.get("WWW-Authenticate").get(0));
        assertEquals(0, r.body.length);
    }

    @Test
    public void chunkedBody() throws IOException {
        RawResponse r = parse(
            "HTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "5\r\n" +
            "hello\r\n" +
            "6\r\n" +
            " world\r\n" +
            "0\r\n" +
            "\r\n");
        assertArrayEquals("hello world".getBytes(StandardCharsets.US_ASCII), r.body);
    }

    @Test
    public void chunkedBody_withChunkExtensionAndTrailers() throws IOException {
        RawResponse r = parse(
            "HTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "b;ext=1\r\n" +
            "hello world\r\n" +
            "0\r\n" +
            "X-Trailer: ignored\r\n" +
            "\r\n");
        assertArrayEquals("hello world".getBytes(StandardCharsets.US_ASCII), r.body);
    }

    @Test
    public void connectionClose_readsToEof() throws IOException {
        RawResponse r = parse(
            "HTTP/1.1 500 Internal Server Error\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "fault body");
        assertEquals(500, r.statusCode);
        assertTrue(r.connectionClose);
        assertArrayEquals("fault body".getBytes(StandardCharsets.US_ASCII), r.body);
    }

    @Test
    public void keepAliveWithoutFramingHeaders_hasEmptyBody() throws IOException {
        // A bare 401 Negotiate challenge: keep-alive, no Content-Length, no chunking.
        RawResponse r = parse(
            "HTTP/1.1 401 Unauthorized\r\n" +
            "WWW-Authenticate: Negotiate YIIabc=\r\n" +
            "\r\n");
        assertEquals(401, r.statusCode);
        assertEquals(0, r.body.length);
        assertFalse(r.connectionClose);
    }

    @Test
    public void statusLineWithoutReasonPhrase() throws IOException {
        RawResponse r = parse(
            "HTTP/1.1 200\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n");
        assertEquals(200, r.statusCode);
        assertEquals("", r.reasonPhrase);
    }

    @Test
    public void multipleHeaderValuesAccumulate() throws IOException {
        RawResponse r = parse(
            "HTTP/1.1 401 Unauthorized\r\n" +
            "WWW-Authenticate: Negotiate\r\n" +
            "WWW-Authenticate: Basic realm=\"x\"\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n");
        assertEquals(2, r.headers.get("WWW-Authenticate").size());
    }

    @Test
    public void http10_defaultsToConnectionClose() throws IOException {
        RawResponse r = parse(
            "HTTP/1.0 200 OK\r\n" +
            "Content-Length: 2\r\n" +
            "\r\n" +
            "ok");
        assertTrue(r.connectionClose);
    }

    @Test
    public void emptyStream_throwsEof() {
        try {
            parse("");
            fail("expected EOFException");
        } catch (EOFException expected) {
            // expected
        } catch (IOException e) {
            fail("expected EOFException, got " + e);
        }
    }

    @Test
    public void malformedStatusLine_throws() {
        try {
            parse("garbage that is not http\r\n\r\n");
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void truncatedContentLengthBody_throwsEof() {
        try {
            parse(
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 100\r\n" +
                "\r\n" +
                "only a few bytes");
            fail("expected EOFException");
        } catch (EOFException expected) {
            // expected
        } catch (IOException e) {
            fail("expected EOFException, got " + e);
        }
    }

    @Test
    public void negativeContentLength_throws() {
        try {
            parse(
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: -5\r\n" +
                "\r\n");
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void oversizedHeaderLine_throws() {
        StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\nX-Junk: ");
        for (int i = 0; i < 70 * 1024; i++) {
            sb.append('a');
        }
        sb.append("\r\n\r\n");
        try {
            parse(sb.toString());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line exceeds"));
        }
    }

    @Test
    public void oversizedHeaderBlock_throws() {
        StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
        // ~20k headers of ~60 bytes each: every line is within the per-line cap, but the
        // block blows past the cumulative cap
        for (int i = 0; i < 20_000; i++) {
            sb.append("X-Header-").append(i).append(": aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\r\n");
        }
        sb.append("\r\n");
        try {
            parse(sb.toString());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("headers exceed"));
        }
    }

    @Test
    public void oversizedChunkedTrailers_throw() {
        StringBuilder sb = new StringBuilder(
            "HTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "2\r\n" +
            "ok\r\n" +
            "0\r\n");
        for (int i = 0; i < 40_000; i++) {
            sb.append("X-Trailer-").append(i).append(": aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\r\n");
        }
        sb.append("\r\n");
        try {
            parse(sb.toString());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("trailers exceed"));
        }
    }

    private static RawResponse parse(String raw) throws IOException {
        return KerberosHttpSession.readResponse(
            new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
    }
}

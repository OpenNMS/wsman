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
package org.opennms.core.wsman.cxf.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.opennms.core.wsman.cxf.shell.ShellResponseParser.ReceiveChunk;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class ShellResponseParserTest {

    // --- extractShellId --------------------------------------------------------------

    @Test
    public void shellId_fromRspShellIdElement() {
        Element body = parse(
            "<s:Body xmlns:s='http://www.w3.org/2003/05/soap-envelope'"
            + " xmlns:rsp='" + ShellConstants.NS_SHELL + "'>"
            + "  <rsp:Shell>"
            + "    <rsp:ShellId>uuid:F7-A3-11-EF</rsp:ShellId>"
            + "    <rsp:InputStreams>stdin</rsp:InputStreams>"
            + "    <rsp:OutputStreams>stdout stderr</rsp:OutputStreams>"
            + "  </rsp:Shell>"
            + "</s:Body>");

        Optional<String> id = ShellResponseParser.extractShellId(body);

        assertTrue(id.isPresent());
        assertEquals("uuid:F7-A3-11-EF", id.get());
    }

    @Test
    public void shellId_fallbackToSelectorSet() {
        // Some servers return the ShellId only in the wsa:ResourceCreated SelectorSet,
        // not in a top-level <rsp:ShellId>.
        Element body = parse(
            "<s:Body xmlns:s='http://www.w3.org/2003/05/soap-envelope'"
            + " xmlns:wsa='http://schemas.xmlsoap.org/ws/2004/08/addressing'"
            + " xmlns:wsman='http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd'>"
            + "  <wsa:ResourceCreated>"
            + "    <wsa:Address>http://server:5985/wsman</wsa:Address>"
            + "    <wsa:ReferenceParameters>"
            + "      <wsman:ResourceURI>" + ShellConstants.SHELL_RESOURCE_URI + "</wsman:ResourceURI>"
            + "      <wsman:SelectorSet>"
            + "        <wsman:Selector Name='ShellId'>uuid:DEAD-BEEF</wsman:Selector>"
            + "      </wsman:SelectorSet>"
            + "    </wsa:ReferenceParameters>"
            + "  </wsa:ResourceCreated>"
            + "</s:Body>");

        Optional<String> id = ShellResponseParser.extractShellId(body);

        assertTrue(id.isPresent());
        assertEquals("uuid:DEAD-BEEF", id.get());
    }

    @Test
    public void shellId_missing_returnsEmpty() {
        Element body = parse(
            "<s:Body xmlns:s='http://www.w3.org/2003/05/soap-envelope'/>");

        assertFalse(ShellResponseParser.extractShellId(body).isPresent());
    }

    @Test
    public void shellId_nullRoot_returnsEmpty() {
        assertFalse(ShellResponseParser.extractShellId(null).isPresent());
    }

    // --- extractCommandId ------------------------------------------------------------

    @Test
    public void commandId_fromRspCommandId() {
        Element body = parse(
            "<s:Body xmlns:s='http://www.w3.org/2003/05/soap-envelope'"
            + " xmlns:rsp='" + ShellConstants.NS_SHELL + "'>"
            + "  <rsp:CommandResponse>"
            + "    <rsp:CommandId>CMD-12345</rsp:CommandId>"
            + "  </rsp:CommandResponse>"
            + "</s:Body>");

        Optional<String> id = ShellResponseParser.extractCommandId(body);

        assertTrue(id.isPresent());
        assertEquals("CMD-12345", id.get());
    }

    @Test
    public void commandId_missing_returnsEmpty() {
        Element body = parse("<s:Body xmlns:s='http://www.w3.org/2003/05/soap-envelope'/>");
        assertFalse(ShellResponseParser.extractCommandId(body).isPresent());
    }

    @Test
    public void commandId_nullRoot_returnsEmpty() {
        assertFalse(ShellResponseParser.extractCommandId(null).isPresent());
    }

    // --- parseReceiveResponse --------------------------------------------------------

    @Test
    public void receive_singleStdoutChunk_runningState() {
        String payload = "hello world\r\n";
        Element body = parse(receiveResponseXml(
            stream("stdout", payload, false),
            commandStateRunning()));

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), chunk.stdoutBytes());
        assertEquals(0, chunk.stderrBytes().length);
        assertFalse(chunk.isStdoutEnd());
        assertFalse(chunk.isStderrEnd());
        assertFalse(chunk.isDone());
        assertNull(chunk.getExitCode());
    }

    @Test
    public void receive_stdoutAndStderrInSameResponse_accumulatesBoth() {
        Element body = parse(receiveResponseXml(
            stream("stdout", "out", false),
            stream("stderr", "err", false),
            commandStateRunning()));

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertEquals("out", new String(chunk.stdoutBytes(), StandardCharsets.UTF_8));
        assertEquals("err", new String(chunk.stderrBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void receive_done_withExitCodeZero() {
        Element body = parse(receiveResponseXml(
            stream("stdout", "ok\r\n", true),
            stream("stderr", "", true),
            commandStateDone(0)));

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertTrue(chunk.isDone());
        assertEquals(Integer.valueOf(0), chunk.getExitCode());
        assertTrue(chunk.isStdoutEnd());
        assertTrue(chunk.isStderrEnd());
        assertEquals("ok\r\n", new String(chunk.stdoutBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void receive_done_withNonZeroExitCode() {
        Element body = parse(receiveResponseXml(
            stream("stderr", "command not found\r\n", true),
            commandStateDone(1)));

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertTrue(chunk.isDone());
        assertEquals(Integer.valueOf(1), chunk.getExitCode());
        assertEquals("command not found\r\n",
            new String(chunk.stderrBytes(), StandardCharsets.UTF_8));
        assertEquals(0, chunk.stdoutBytes().length);
    }

    @Test
    public void receive_endFlaggedEmptyStream_marksEndWithoutBytes() {
        Element body = parse(receiveResponseXml(
            stream("stdout", "", true)));

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertTrue(chunk.isStdoutEnd());
        assertEquals(0, chunk.stdoutBytes().length);
        assertFalse(chunk.isDone());
    }

    @Test
    public void receive_malformedBase64_isSilentlyEmpty() {
        // Server occasionally sends garbage during truncation/disconnect — we shouldn't
        // throw, just drop the chunk and keep parsing the rest of the response.
        Element body = parse(
            "<s:Body xmlns:s='http://www.w3.org/2003/05/soap-envelope'"
            + " xmlns:rsp='" + ShellConstants.NS_SHELL + "'>"
            + "  <rsp:ReceiveResponse>"
            + "    <rsp:Stream Name='stdout' CommandId='CMD-1'>!!!not base64!!!</rsp:Stream>"
            + "    " + commandStateDone(0)
            + "  </rsp:ReceiveResponse>"
            + "</s:Body>");

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertEquals(0, chunk.stdoutBytes().length);
        assertTrue(chunk.isDone());
        assertEquals(Integer.valueOf(0), chunk.getExitCode());
    }

    @Test
    public void receive_doneWithoutExitCode_isDoneNullExitCode() {
        Element body = parse(
            "<s:Body xmlns:s='http://www.w3.org/2003/05/soap-envelope'"
            + " xmlns:rsp='" + ShellConstants.NS_SHELL + "'>"
            + "  <rsp:ReceiveResponse>"
            + "    <rsp:CommandState State='" + ShellConstants.STATE_DONE + "' CommandId='CMD-1'/>"
            + "  </rsp:ReceiveResponse>"
            + "</s:Body>");

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertTrue(chunk.isDone());
        assertNull(chunk.getExitCode());
    }

    @Test
    public void receive_nonNumericExitCode_isDoneNullExitCode() {
        Element body = parse(
            "<s:Body xmlns:s='http://www.w3.org/2003/05/soap-envelope'"
            + " xmlns:rsp='" + ShellConstants.NS_SHELL + "'>"
            + "  <rsp:ReceiveResponse>"
            + "    <rsp:CommandState State='" + ShellConstants.STATE_DONE + "' CommandId='CMD-1'>"
            + "      <rsp:ExitCode>not-a-number</rsp:ExitCode>"
            + "    </rsp:CommandState>"
            + "  </rsp:ReceiveResponse>"
            + "</s:Body>");

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertTrue(chunk.isDone());
        assertNull(chunk.getExitCode());
    }

    @Test
    public void receive_runningState_notDone() {
        Element body = parse(receiveResponseXml(
            stream("stdout", "partial...", false),
            commandStateRunning()));

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertFalse(chunk.isDone());
        assertNull(chunk.getExitCode());
    }

    @Test
    public void receive_multipleStdoutStreams_accumulatedInOrder() {
        Element body = parse(receiveResponseXml(
            stream("stdout", "first ", false),
            stream("stdout", "second", false)));

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertEquals("first second", new String(chunk.stdoutBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void receive_streamNameCaseInsensitive() {
        // Some servers/encoders capitalize stream names. We should still bucket them
        // correctly — the spec is silent on case but the matching code uses equalsIgnoreCase.
        Element body = parse(receiveResponseXml(
            stream("STDOUT", "shouty", false)));

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertEquals("shouty", new String(chunk.stdoutBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void receive_emptyResponse_yieldsEmptyChunk() {
        Element body = parse(
            "<s:Body xmlns:s='http://www.w3.org/2003/05/soap-envelope'"
            + " xmlns:rsp='" + ShellConstants.NS_SHELL + "'>"
            + "  <rsp:ReceiveResponse/>"
            + "</s:Body>");

        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(body);

        assertEquals(0, chunk.stdoutBytes().length);
        assertEquals(0, chunk.stderrBytes().length);
        assertFalse(chunk.isDone());
        assertNull(chunk.getExitCode());
    }

    @Test
    public void receive_nullRoot_yieldsEmptyChunk() {
        ReceiveChunk chunk = ShellResponseParser.parseReceiveResponse(null);

        assertEquals(0, chunk.stdoutBytes().length);
        assertEquals(0, chunk.stderrBytes().length);
        assertFalse(chunk.isDone());
        assertNull(chunk.getExitCode());
    }

    // --- helpers ---------------------------------------------------------------------

    private static String stream(String name, String plaintext, boolean end) {
        String b64 = Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
        return "<rsp:Stream Name='" + name + "' CommandId='CMD-1'"
            + (end ? " End='true'" : "") + ">" + b64 + "</rsp:Stream>";
    }

    private static String commandStateRunning() {
        return "<rsp:CommandState State='" + ShellConstants.STATE_RUNNING + "' CommandId='CMD-1'/>";
    }

    private static String commandStateDone(int exitCode) {
        return "<rsp:CommandState State='" + ShellConstants.STATE_DONE + "' CommandId='CMD-1'>"
            + "<rsp:ExitCode>" + exitCode + "</rsp:ExitCode>"
            + "</rsp:CommandState>";
    }

    private static String receiveResponseXml(String... fragments) {
        StringBuilder sb = new StringBuilder();
        sb.append("<s:Body xmlns:s='http://www.w3.org/2003/05/soap-envelope'")
          .append(" xmlns:rsp='").append(ShellConstants.NS_SHELL).append("'>")
          .append("<rsp:ReceiveResponse>");
        for (String f : fragments) sb.append(f);
        sb.append("</rsp:ReceiveResponse></s:Body>");
        return sb.toString();
    }

    private static Element parse(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return doc.getDocumentElement();
        } catch (Exception e) {
            throw new AssertionError("test fixture XML failed to parse: " + e.getMessage(), e);
        }
    }
}
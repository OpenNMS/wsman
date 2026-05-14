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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.opennms.core.wsman.WSManConstants;
import org.opennms.core.wsman.exceptions.WSManException;
import org.opennms.core.wsman.shell.CommandResult;
import org.opennms.core.wsman.shell.ShellOptions;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class WinRSClientTest {

    private static final String SHELL_ID = "uuid:SHELL-ABC";
    private static final String COMMAND_ID = "uuid:CMD-1";

    // --- happy path ------------------------------------------------------------------

    @Test
    public void runCommand_singleReceiveDone_returnsStdoutStderrExitCode() {
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> commandResponse(COMMAND_ID))
            .nextReceive(receiveResponse(/*stdout*/ "hello\r\n", /*stderr*/ "", true, 0));

        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            CommandResult r = w.runCommand("ipconfig", new String[]{"/all"}, Duration.ofSeconds(5));
            assertEquals(0, r.exitCode());
            assertEquals("hello\r\n", r.stdout());
            assertEquals("", r.stderr());
        }

        assertEquals("Create+Command+Receive+Delete = 4 ops", 4, ops.callLog.size());
        assertEquals("create", ops.callLog.get(0));
        assertEquals("command", ops.callLog.get(1));
        assertEquals("receive", ops.callLog.get(2));
        assertEquals("delete", ops.callLog.get(3));
    }

    @Test
    public void runCommand_multipleReceivesAccumulateBeforeDone() {
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> commandResponse(COMMAND_ID))
            .nextReceive(receiveResponse("part1 ", "", false, null))
            .nextReceive(receiveResponse("part2", "warn\n", false, null))
            .nextReceive(receiveResponse("", "", true, 0));

        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            CommandResult r = w.runCommand("hostname", null, Duration.ofSeconds(5));
            assertEquals(0, r.exitCode());
            assertEquals("part1 part2", r.stdout());
            assertEquals("warn\n", r.stderr());
        }

        assertEquals(3, ops.receiveCount);
    }

    @Test
    public void runCommand_nonZeroExitCode_propagatedInResult() {
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> commandResponse(COMMAND_ID))
            .nextReceive(receiveResponse("", "command not found\r\n", true, 9009));

        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            CommandResult r = w.runCommand("nope", null, Duration.ofSeconds(5));
            assertEquals(9009, r.exitCode());
            assertEquals("", r.stdout());
            assertEquals("command not found\r\n", r.stderr());
        }
    }

    @Test
    public void runCommand_doneWithoutExitCode_yieldsSentinelMinusOne() {
        // Defensive: spec-compliant servers always include ExitCode with Done, but our
        // parser tolerates its absence — propagate that into a -1 sentinel for callers.
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> commandResponse(COMMAND_ID))
            .nextReceive(receiveResponseDoneNoExitCode());

        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            CommandResult r = w.runCommand("x", null, Duration.ofSeconds(5));
            assertEquals(-1, r.exitCode());
        }
    }

    // --- shell lifecycle / close -----------------------------------------------------

    @Test
    public void close_deletesShellOnceAndIsIdempotent() {
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> commandResponse(COMMAND_ID))
            .nextReceive(receiveResponse("ok", "", true, 0));

        WinRSClient w = new WinRSClient(ops, ShellOptions.defaults());
        w.runCommand("x", null, Duration.ofSeconds(5));
        w.close();
        w.close(); // second close is a no-op

        assertEquals(1, ops.deleteCount);
        assertEquals(SHELL_ID, ops.lastDeletedShellId);
    }

    @Test
    public void close_withoutRunCommand_doesNotInvokeDelete() {
        FakeOps ops = new FakeOps();

        WinRSClient w = new WinRSClient(ops, ShellOptions.defaults());
        w.close();

        assertEquals("Delete must not be sent when no shell was ever created",
            0, ops.deleteCount);
    }

    @Test
    public void close_swallowsDeleteFailures() {
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> commandResponse(COMMAND_ID))
            .nextReceive(receiveResponse("", "", true, 0))
            .deleteThrows(new RuntimeException("server is on fire"));

        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            w.runCommand("x", null, Duration.ofSeconds(5));
            // implicit close() — must not propagate the Delete failure
        }
        assertEquals(1, ops.deleteCount);
    }

    // --- error / edge cases ----------------------------------------------------------

    @Test
    public void createShellWithoutShellIdInResponse_throwsWSManException() {
        FakeOps ops = new FakeOps().onCreate(b -> emptyBody());
        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            w.runCommand("x", null, Duration.ofSeconds(5));
            fail("expected WSManException");
        } catch (WSManException e) {
            assertTrue(e.getMessage().contains("ShellId"));
        }
        assertEquals("Delete is not sent when shell was never opened",
            0, ops.deleteCount);
    }

    @Test
    public void commandWithoutCommandIdInResponse_throwsWSManException() {
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> emptyBody());

        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            w.runCommand("x", null, Duration.ofSeconds(5));
            fail("expected WSManException");
        } catch (WSManException e) {
            assertTrue(e.getMessage().contains("CommandId"));
        }
        assertEquals("Delete must still run to clean up the orphaned shell",
            1, ops.deleteCount);
    }

    @Test
    public void receiveFailure_propagatedAsWSManException() {
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> commandResponse(COMMAND_ID))
            .receiveThrows(new RuntimeException("network died"));

        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            w.runCommand("x", null, Duration.ofSeconds(5));
            fail("expected WSManException");
        } catch (WSManException e) {
            assertTrue(e.getMessage().toLowerCase().contains("receive"));
            assertNotNull(e.getCause());
        }
        assertEquals(1, ops.deleteCount);
    }

    @Test
    public void receiveLoopTimeout_sendsTerminateSignalAndThrows() {
        // Receive always returns Running, never Done; the deadline elapses.
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> commandResponse(COMMAND_ID))
            .receiveAlways(receiveResponse("still going...", "", false, null));

        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            w.runCommand("long-running", null, Duration.ofMillis(1));
            fail("expected WSManException on timeout");
        } catch (WSManException e) {
            assertTrue(e.getMessage().contains("did not complete"));
        }

        assertEquals("Terminate signal must be sent on timeout",
            1, ops.signalCount);
        assertEquals("Delete is the final cleanup step on timeout",
            1, ops.deleteCount);
    }

    // --- argument plumbing -----------------------------------------------------------

    @Test
    public void commandBody_carriesExecutableAndArgs() {
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> commandResponse(COMMAND_ID))
            .nextReceive(receiveResponse("", "", true, 0));

        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            w.runCommand("net", new String[]{"user", "admin"}, Duration.ofSeconds(5));
        }

        Element commandBody = ops.lastCommandBody;
        assertNotNull(commandBody);
        Element cmd = (Element) commandBody.getElementsByTagNameNS(
            ShellConstants.NS_SHELL, "Command").item(0);
        assertEquals("net", cmd.getTextContent());
        assertEquals(2, commandBody.getElementsByTagNameNS(
            ShellConstants.NS_SHELL, "Arguments").getLength());
    }

    @Test
    public void receiveBody_carriesCommandIdSelector() {
        FakeOps ops = new FakeOps()
            .onCreate(b -> shellResponse(SHELL_ID))
            .onCommand(b -> commandResponse(COMMAND_ID))
            .nextReceive(receiveResponse("", "", true, 0));

        try (WinRSClient w = new WinRSClient(ops, ShellOptions.defaults())) {
            w.runCommand("x", null, Duration.ofSeconds(5));
        }

        Element desired = (Element) ops.lastReceiveBody.getElementsByTagNameNS(
            ShellConstants.NS_SHELL, "DesiredStream").item(0);
        assertEquals(COMMAND_ID, desired.getAttribute("CommandId"));
    }

    // --- fake ShellOperations --------------------------------------------------------

    /**
     * Scriptable in-memory {@link ShellOperations} for orchestrator tests. Each test
     * configures responses for Create/Command via lambdas and queues Receive responses
     * via {@link #nextReceive} (or sets one repeating response via {@link #receiveAlways}).
     */
    private static final class FakeOps implements ShellOperations {
        final List<String> callLog = new ArrayList<>();
        Function<Element, Element> createResponder;
        Function<Element, Element> commandResponder;
        final List<Element> receiveQueue = new ArrayList<>();
        Element repeatedReceive;
        RuntimeException receiveError;
        RuntimeException deleteError;

        Element lastCommandBody;
        Element lastReceiveBody;
        int receiveCount;
        int signalCount;
        int deleteCount;
        String lastDeletedShellId;

        FakeOps onCreate(Function<Element, Element> r) { this.createResponder = r; return this; }
        FakeOps onCommand(Function<Element, Element> r) { this.commandResponder = r; return this; }
        FakeOps nextReceive(Element response) { this.receiveQueue.add(response); return this; }
        FakeOps receiveAlways(Element response) { this.repeatedReceive = response; return this; }
        FakeOps receiveThrows(RuntimeException e) { this.receiveError = e; return this; }
        FakeOps deleteThrows(RuntimeException e) { this.deleteError = e; return this; }

        @Override
        public Element create(Element shellBody, ShellOptions options) {
            callLog.add("create");
            return createResponder.apply(shellBody);
        }
        @Override
        public Element command(String shellId, Element commandBody) {
            callLog.add("command");
            lastCommandBody = commandBody;
            return commandResponder.apply(commandBody);
        }
        @Override
        public Element receive(String shellId, Element receiveBody) {
            callLog.add("receive");
            lastReceiveBody = receiveBody;
            receiveCount++;
            if (receiveError != null) throw receiveError;
            if (!receiveQueue.isEmpty()) return receiveQueue.remove(0);
            if (repeatedReceive != null) return repeatedReceive;
            throw new IllegalStateException("FakeOps: unexpected Receive call (no response queued)");
        }
        @Override
        public Element signal(String shellId, Element signalBody) {
            callLog.add("signal");
            signalCount++;
            return null;
        }
        @Override
        public void delete(String shellId) {
            callLog.add("delete");
            deleteCount++;
            lastDeletedShellId = shellId;
            if (deleteError != null) throw deleteError;
        }
    }

    // --- response fixtures -----------------------------------------------------------

    private static Element shellResponse(String shellId) {
        return parse(
            "<rsp:Shell xmlns:rsp='" + ShellConstants.NS_SHELL + "'>"
            + "<rsp:ShellId>" + shellId + "</rsp:ShellId>"
            + "</rsp:Shell>");
    }

    private static Element commandResponse(String commandId) {
        return parse(
            "<rsp:CommandResponse xmlns:rsp='" + ShellConstants.NS_SHELL + "'>"
            + "<rsp:CommandId>" + commandId + "</rsp:CommandId>"
            + "</rsp:CommandResponse>");
    }

    private static Element receiveResponse(String stdout, String stderr,
                                           boolean done, Integer exitCode) {
        StringBuilder sb = new StringBuilder()
            .append("<rsp:ReceiveResponse xmlns:rsp='").append(ShellConstants.NS_SHELL).append("'>");
        if (!stdout.isEmpty()) {
            sb.append(stream("stdout", stdout));
        }
        if (!stderr.isEmpty()) {
            sb.append(stream("stderr", stderr));
        }
        if (done) {
            sb.append("<rsp:CommandState State='").append(ShellConstants.STATE_DONE)
              .append("' CommandId='").append(COMMAND_ID).append("'>");
            if (exitCode != null) {
                sb.append("<rsp:ExitCode>").append(exitCode).append("</rsp:ExitCode>");
            }
            sb.append("</rsp:CommandState>");
        } else {
            sb.append("<rsp:CommandState State='").append(ShellConstants.STATE_RUNNING)
              .append("' CommandId='").append(COMMAND_ID).append("'/>");
        }
        sb.append("</rsp:ReceiveResponse>");
        return parse(sb.toString());
    }

    private static Element receiveResponseDoneNoExitCode() {
        return parse(
            "<rsp:ReceiveResponse xmlns:rsp='" + ShellConstants.NS_SHELL + "'>"
            + "<rsp:CommandState State='" + ShellConstants.STATE_DONE + "' CommandId='" + COMMAND_ID + "'/>"
            + "</rsp:ReceiveResponse>");
    }

    private static String stream(String name, String plaintext) {
        String b64 = Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
        return "<rsp:Stream Name='" + name + "' CommandId='" + COMMAND_ID + "'>" + b64 + "</rsp:Stream>";
    }

    private static Element emptyBody() {
        return parse("<empty xmlns='" + WSManConstants.XML_NS_DMTF_WSMAN_V1 + "'/>");
    }

    private static Element parse(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return doc.getDocumentElement();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // suppress unused-import warning on assertArrayEquals — kept available for future
    // byte-level assertions on Element bodies
    @SuppressWarnings("unused")
    private static void useAssertArrayEquals() { assertArrayEquals(new byte[0], new byte[0]); }
    @SuppressWarnings("unused")
    private static void useAssertNull() { assertNull(null); }
}
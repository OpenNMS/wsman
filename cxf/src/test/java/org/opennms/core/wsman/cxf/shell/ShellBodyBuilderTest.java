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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.opennms.core.wsman.shell.ShellOptions;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class ShellBodyBuilderTest {

    private static final String NS = ShellConstants.NS_SHELL;

    // --- Create body -----------------------------------------------------------------

    @Test
    public void createBody_defaults_hasInputOutputStreamsAndDefaultIdleTimeout() {
        Element shell = ShellBodyBuilder.buildCreateBody(ShellOptions.defaults());

        assertEquals(NS, shell.getNamespaceURI());
        assertEquals("Shell", shell.getLocalName());

        assertEquals("stdin", textOfChild(shell, "InputStreams"));
        assertEquals("stdout stderr", textOfChild(shell, "OutputStreams"));
        assertEquals("default options have no WorkingDirectory",
            0, shell.getElementsByTagNameNS(NS, "WorkingDirectory").getLength());
        assertEquals("default options have no Environment",
            0, shell.getElementsByTagNameNS(NS, "Environment").getLength());
        // ShellOptions.defaults() carries a 3-minute idle timeout — assert it's wired
        // through to the wire so the API contract isn't silently dropped on the floor.
        assertEquals("PT180.000S", textOfChild(shell, "IdleTimeOut"));
    }

    @Test
    public void createBody_withExplicitIdleTimeout_emitsXsdDuration() {
        ShellOptions opts = new ShellOptions.Builder()
            .withIdleTimeout(java.time.Duration.ofSeconds(45))
            .build();

        Element shell = ShellBodyBuilder.buildCreateBody(opts);

        assertEquals("PT45.000S", textOfChild(shell, "IdleTimeOut"));
    }

    @Test
    public void createBody_nullIdleTimeout_omitsTheElement() {
        // Explicitly clearing the idle timeout opts the caller into the server default.
        ShellOptions opts = new ShellOptions.Builder()
            .withIdleTimeout(null)
            .build();

        Element shell = ShellBodyBuilder.buildCreateBody(opts);

        assertEquals("explicit null idleTimeout must omit the element",
            0, shell.getElementsByTagNameNS(NS, "IdleTimeOut").getLength());
    }

    @Test
    public void createBody_withWorkingDirectory_includesWorkingDirectoryElement() {
        ShellOptions opts = new ShellOptions.Builder()
            .withWorkingDirectory("C:\\Windows\\Temp")
            .build();

        Element shell = ShellBodyBuilder.buildCreateBody(opts);

        assertEquals("C:\\Windows\\Temp", textOfChild(shell, "WorkingDirectory"));
    }

    @Test
    public void createBody_withEnvironment_emitsVariableElements() {
        ShellOptions opts = new ShellOptions.Builder()
            .withEnvironmentVariable("FOO", "bar")
            .withEnvironmentVariable("BAZ", "qux")
            .build();

        Element shell = ShellBodyBuilder.buildCreateBody(opts);

        Element env = firstChild(shell, "Environment");
        assertNotNull("expected an Environment element", env);

        NodeList vars = env.getElementsByTagNameNS(NS, "Variable");
        assertEquals(2, vars.getLength());

        Element first = (Element) vars.item(0);
        assertEquals("FOO", first.getAttribute("Name"));
        assertEquals("bar", first.getTextContent());

        Element second = (Element) vars.item(1);
        assertEquals("BAZ", second.getAttribute("Name"));
        assertEquals("qux", second.getTextContent());
    }

    @Test
    public void createBody_emptyWorkingDirectory_isOmitted() {
        ShellOptions opts = new ShellOptions.Builder()
            .withWorkingDirectory("")
            .build();

        Element shell = ShellBodyBuilder.buildCreateBody(opts);

        assertEquals(0, shell.getElementsByTagNameNS(NS, "WorkingDirectory").getLength());
    }

    // --- Command body ----------------------------------------------------------------

    @Test
    public void commandBody_noArgs_hasCommandOnly() {
        Element cmd = ShellBodyBuilder.buildCommandBody("ipconfig", null);

        assertEquals(NS, cmd.getNamespaceURI());
        assertEquals("CommandLine", cmd.getLocalName());
        assertEquals("ipconfig", textOfChild(cmd, "Command"));
        assertEquals(0, cmd.getElementsByTagNameNS(NS, "Arguments").getLength());
    }

    @Test
    public void commandBody_singleArg_emitsArgumentsElement() {
        Element cmd = ShellBodyBuilder.buildCommandBody("ipconfig", new String[]{"/all"});

        NodeList args = cmd.getElementsByTagNameNS(NS, "Arguments");
        assertEquals(1, args.getLength());
        assertEquals("/all", args.item(0).getTextContent());
    }

    @Test
    public void commandBody_multipleArgs_emitsArgumentsInOrder() {
        Element cmd = ShellBodyBuilder.buildCommandBody("net",
            new String[]{"user", "admin", "/domain"});

        NodeList args = cmd.getElementsByTagNameNS(NS, "Arguments");
        assertEquals(3, args.getLength());
        assertEquals("user", args.item(0).getTextContent());
        assertEquals("admin", args.item(1).getTextContent());
        assertEquals("/domain", args.item(2).getTextContent());
    }

    @Test
    public void commandBody_emptyArgsArray_treatedAsNoArgs() {
        Element cmd = ShellBodyBuilder.buildCommandBody("whoami", new String[0]);

        assertEquals("whoami", textOfChild(cmd, "Command"));
        assertEquals(0, cmd.getElementsByTagNameNS(NS, "Arguments").getLength());
    }

    // --- Receive body ----------------------------------------------------------------

    @Test
    public void receiveBody_emitsDesiredStreamWithCommandId() {
        Element receive = ShellBodyBuilder.buildReceiveBody("CMD-abc-123");

        assertEquals(NS, receive.getNamespaceURI());
        assertEquals("Receive", receive.getLocalName());

        Element desired = firstChild(receive, "DesiredStream");
        assertNotNull("expected a DesiredStream child", desired);
        assertEquals("CMD-abc-123", desired.getAttribute("CommandId"));
        assertEquals("stdout stderr", desired.getTextContent());
    }

    // --- Signal body -----------------------------------------------------------------

    @Test
    public void signalBody_terminate_emitsCommandIdAttrAndCodeChild() {
        Element signal = ShellBodyBuilder.buildSignalBody("CMD-xyz", ShellConstants.SIGNAL_TERMINATE);

        assertEquals(NS, signal.getNamespaceURI());
        assertEquals("Signal", signal.getLocalName());
        assertEquals("CMD-xyz", signal.getAttribute("CommandId"));
        assertEquals(ShellConstants.SIGNAL_TERMINATE, textOfChild(signal, "Code"));
    }

    @Test
    public void signalBody_ctrlC_emitsCorrectCode() {
        Element signal = ShellBodyBuilder.buildSignalBody("CMD-1", ShellConstants.SIGNAL_CTRL_C);

        assertEquals(ShellConstants.SIGNAL_CTRL_C, textOfChild(signal, "Code"));
        assertTrue("Code value should be a URI ending in /ctrl_c",
            ShellConstants.SIGNAL_CTRL_C.endsWith("/ctrl_c"));
    }

    // --- Delete body -----------------------------------------------------------------

    @Test
    public void deleteBody_returnsNull() {
        assertNull("Delete has an empty body", ShellBodyBuilder.buildDeleteBody());
    }

    // --- helpers ---------------------------------------------------------------------

    private static Element firstChild(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS(NS, localName);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String textOfChild(Element parent, String localName) {
        Element child = firstChild(parent, localName);
        assertNotNull("expected a " + localName + " child of " + parent.getLocalName(), child);
        return child.getTextContent();
    }
}
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

import java.util.Base64;
import java.util.Optional;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses the body elements of WinRS shell responses.
 *
 * <p>All methods take the SOAP body element (the direct parent of {@code <rsp:Shell>},
 * {@code <rsp:CommandResponse>}, {@code <rsp:ReceiveResponse>}, etc.) or any ancestor that
 * contains the relevant {@code rsp:} elements. Lookup is by namespace + local name so the
 * caller doesn't need to worry about element-prefix variations between servers.
 */
public final class ShellResponseParser {

    private ShellResponseParser() {}

    /**
     * Extracts the {@code ShellId} returned by a Create-shell response. The server returns
     * either {@code <rsp:ShellId>} (older format) or {@code <wsa:ReferenceParameters>} with
     * a SelectorSet containing a {@code ShellId} selector. We handle both.
     *
     * @return the shell ID, or empty if none was found
     */
    public static Optional<String> extractShellId(Element responseRoot) {
        if (responseRoot == null) return Optional.empty();
        Element id = findFirstByLocalName(responseRoot, ShellConstants.NS_SHELL, "ShellId");
        if (id != null) {
            return Optional.of(text(id));
        }
        // Fallback: look for a Selector with Name="ShellId" anywhere in the response.
        NodeList selectors = responseRoot.getElementsByTagNameNS("*", "Selector");
        for (int i = 0; i < selectors.getLength(); i++) {
            Element s = (Element) selectors.item(i);
            if ("ShellId".equalsIgnoreCase(s.getAttribute("Name"))) {
                return Optional.of(text(s));
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts the {@code CommandId} from a CommandResponse.
     */
    public static Optional<String> extractCommandId(Element responseRoot) {
        if (responseRoot == null) return Optional.empty();
        Element id = findFirstByLocalName(responseRoot, ShellConstants.NS_SHELL, "CommandId");
        return id == null ? Optional.empty() : Optional.of(text(id));
    }

    /**
     * Parses a {@code <rsp:ReceiveResponse>}, decoding each {@code <rsp:Stream>} payload
     * from base64 and accumulating stdout/stderr into the supplied {@link ReceiveChunk}
     * builder. If a {@code <rsp:CommandState State=".../Done">} is present, the exit code
     * is captured and {@code done} is set.
     *
     * @return a {@link ReceiveChunk} with whatever bytes/state were available
     */
    public static ReceiveChunk parseReceiveResponse(Element responseRoot) {
        ReceiveChunk chunk = new ReceiveChunk();
        if (responseRoot == null) return chunk;

        NodeList streams = responseRoot.getElementsByTagNameNS(ShellConstants.NS_SHELL, "Stream");
        for (int i = 0; i < streams.getLength(); i++) {
            Element s = (Element) streams.item(i);
            String name = s.getAttribute("Name");
            String b64 = text(s);
            boolean end = "true".equalsIgnoreCase(s.getAttribute("End"));
            if (b64 == null || b64.isEmpty()) {
                if (end) markStreamEnd(chunk, name);
                continue;
            }
            byte[] data;
            try {
                data = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                // Malformed base64 — surface as zero bytes rather than aborting the parse.
                data = new byte[0];
            }
            if (ShellConstants.STREAM_STDOUT.equalsIgnoreCase(name)) {
                chunk.appendStdout(data);
            } else if (ShellConstants.STREAM_STDERR.equalsIgnoreCase(name)) {
                chunk.appendStderr(data);
            }
            if (end) markStreamEnd(chunk, name);
        }

        Element commandState =
            findFirstByLocalName(responseRoot, ShellConstants.NS_SHELL, "CommandState");
        if (commandState != null) {
            String state = commandState.getAttribute("State");
            if (ShellConstants.STATE_DONE.equals(state)) {
                chunk.markDone();
                Element exit =
                    findFirstByLocalName(commandState, ShellConstants.NS_SHELL, "ExitCode");
                if (exit != null) {
                    try {
                        chunk.setExitCode(Integer.parseInt(text(exit).trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return chunk;
    }

    private static void markStreamEnd(ReceiveChunk chunk, String name) {
        if (ShellConstants.STREAM_STDOUT.equalsIgnoreCase(name)) {
            chunk.markStdoutEnd();
        } else if (ShellConstants.STREAM_STDERR.equalsIgnoreCase(name)) {
            chunk.markStderrEnd();
        }
    }

    private static Element findFirstByLocalName(Element root, String ns, String localName) {
        NodeList list = root.getElementsByTagNameNS(ns, localName);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String text(Node n) {
        String s = n.getTextContent();
        return s == null ? "" : s;
    }

    /**
     * Aggregated output bytes from one or more {@code ReceiveResponse} messages, plus
     * terminal command state. Callers accumulate {@code ReceiveChunk}s across the Receive
     * poll loop until {@link #isDone()} is true.
     */
    public static final class ReceiveChunk {
        private final java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        private final java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
        private boolean stdoutEnd;
        private boolean stderrEnd;
        private boolean done;
        private Integer exitCode;

        public void appendStdout(byte[] b) { stdout.write(b, 0, b.length); }
        public void appendStderr(byte[] b) { stderr.write(b, 0, b.length); }
        public void markStdoutEnd() { stdoutEnd = true; }
        public void markStderrEnd() { stderrEnd = true; }
        public void markDone() { done = true; }
        public void setExitCode(int exitCode) { this.exitCode = exitCode; }

        public byte[] stdoutBytes() { return stdout.toByteArray(); }
        public byte[] stderrBytes() { return stderr.toByteArray(); }
        public boolean isStdoutEnd() { return stdoutEnd; }
        public boolean isStderrEnd() { return stderrEnd; }
        public boolean isDone() { return done; }
        public Integer getExitCode() { return exitCode; }
    }
}
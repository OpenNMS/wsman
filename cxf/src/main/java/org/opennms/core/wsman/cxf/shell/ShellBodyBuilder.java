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

import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.opennms.core.wsman.shell.ShellOptions;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Builds the SOAP body element for each WinRS shell operation as a DOM {@link Element}.
 *
 * <p>The transport layer (CXF JAX-WS) is responsible for adding the SOAP envelope,
 * WS-Addressing headers (Action, MessageID, ReplyTo, To), and the WS-Man headers
 * (ResourceURI, SelectorSet, OptionSet, MaxEnvelopeSize, OperationTimeout, Locale).
 * This class only produces the body content that goes inside {@code <soap:Body>}.
 */
public final class ShellBodyBuilder {

    private static final DocumentBuilderFactory DBF;
    static {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException e) {
            throw new ExceptionInInitializerError(e);
        }
        dbf.setExpandEntityReferences(false);
        DBF = dbf;
    }

    private ShellBodyBuilder() {}

    /**
     * Body for the {@code Create} action: a {@code <rsp:Shell>} element with the
     * stdin/stdout/stderr stream names, optional working directory and environment.
     *
     * The no-profile and code-page options go in the {@code <wsman:OptionSet>} SOAP
     * header (see {@link ShellSoapHeaders#optionSet(ShellOptions)}); the idle timeout
     * is emitted here as an {@code <rsp:IdleTimeOut>} child of {@code <rsp:Shell>}.
     */
    public static Element buildCreateBody(ShellOptions options) {
        Document doc = newDocument();
        Element shell = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:Shell");
        doc.appendChild(shell);

        Element inputStreams = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:InputStreams");
        inputStreams.setTextContent(ShellConstants.STREAM_STDIN);
        shell.appendChild(inputStreams);

        Element outputStreams = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:OutputStreams");
        outputStreams.setTextContent(ShellConstants.STREAM_STDOUT + " " + ShellConstants.STREAM_STDERR);
        shell.appendChild(outputStreams);

        if (options.getWorkingDirectory() != null && !options.getWorkingDirectory().isEmpty()) {
            Element wd = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:WorkingDirectory");
            wd.setTextContent(options.getWorkingDirectory());
            shell.appendChild(wd);
        }

        if (!options.getEnvironment().isEmpty()) {
            Element env = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:Environment");
            for (Map.Entry<String, String> e : options.getEnvironment().entrySet()) {
                Element var = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:Variable");
                var.setAttribute("Name", e.getKey());
                var.setTextContent(e.getValue());
                env.appendChild(var);
            }
            shell.appendChild(env);
        }

        // <rsp:IdleTimeOut> caps how long Windows keeps an unused shell alive — useful
        // for orphan-shell reaping if Delete fails (network blip, JVM crash). Omitted
        // when explicitly cleared (null) so the caller can opt into the server default.
        if (options.getIdleTimeout() != null) {
            Element idle = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:IdleTimeOut");
            idle.setTextContent(ShellSoapHeaders.xsdDuration(options.getIdleTimeout()));
            shell.appendChild(idle);
        }

        return shell;
    }

    /**
     * Body for the {@code Command} action: {@code <rsp:CommandLine>} with the executable
     * and optional arguments. The shell ID identifying the target shell is carried in the
     * {@code <wsman:SelectorSet>} SOAP header, not the body.
     */
    public static Element buildCommandBody(String executable, String[] args) {
        Document doc = newDocument();
        Element commandLine = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:CommandLine");
        doc.appendChild(commandLine);

        Element command = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:Command");
        command.setTextContent(executable);
        commandLine.appendChild(command);

        if (args != null) {
            for (String arg : args) {
                Element argEl = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:Arguments");
                argEl.setTextContent(arg);
                commandLine.appendChild(argEl);
            }
        }

        return commandLine;
    }

    /**
     * Body for the {@code Receive} action: {@code <rsp:Receive>} requesting stdout/stderr
     * streams for the given command. The shell ID is carried in the SelectorSet header.
     */
    public static Element buildReceiveBody(String commandId) {
        Document doc = newDocument();
        Element receive = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:Receive");
        doc.appendChild(receive);

        Element desired = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:DesiredStream");
        desired.setAttribute("CommandId", commandId);
        desired.setTextContent(ShellConstants.STREAM_STDOUT + " " + ShellConstants.STREAM_STDERR);
        receive.appendChild(desired);

        return receive;
    }

    /**
     * Body for the {@code Signal} action: {@code <rsp:Signal>} carrying the signal code
     * (typically {@link ShellConstants#SIGNAL_TERMINATE} to terminate a running command).
     */
    public static Element buildSignalBody(String commandId, String signalCode) {
        Document doc = newDocument();
        Element signal = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:Signal");
        signal.setAttribute("CommandId", commandId);
        doc.appendChild(signal);

        Element code = doc.createElementNS(ShellConstants.NS_SHELL, "rsp:Code");
        code.setTextContent(signalCode);
        signal.appendChild(code);

        return signal;
    }

    /**
     * Body for the {@code Delete} action: empty body. The shell ID identifying the
     * shell to delete is carried in the SelectorSet header.
     */
    public static Element buildDeleteBody() {
        return null;
    }

    private static Document newDocument() {
        try {
            DocumentBuilder db = DBF.newDocumentBuilder();
            return db.newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Failed to create XML document builder", e);
        }
    }
}
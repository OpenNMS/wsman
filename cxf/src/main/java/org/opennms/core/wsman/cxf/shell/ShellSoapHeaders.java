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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.opennms.core.wsman.WSManConstants;
import org.opennms.core.wsman.shell.ShellOptions;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Builds the per-call WS-Man SOAP header elements for shell operations as DOM
 * {@link Element}s: {@code <wsman:ResourceURI>}, {@code <wsman:SelectorSet>},
 * {@code <wsman:OptionSet>}. The transport layer wraps each into a CXF
 * {@code org.apache.cxf.headers.Header} and CXF emits them in the outgoing
 * {@code soap:Header} of the envelope.
 *
 * <p>The {@code <wsa:*>} addressing headers (Action, MessageID, To, ReplyTo)
 * are set separately via {@code AddressingProperties} on the request context —
 * they are not produced here.
 */
public final class ShellSoapHeaders {

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

    private ShellSoapHeaders() {}

    /**
     * Builds {@code <wsman:ResourceURI>http://schemas.microsoft.com/wbem/wsman/1/windows/shell/cmd</wsman:ResourceURI>}.
     */
    public static Element resourceUri() {
        Document doc = newDocument();
        Element el = doc.createElementNS(WSManConstants.XML_NS_DMTF_WSMAN_V1, "wsman:ResourceURI");
        el.setTextContent(ShellConstants.SHELL_RESOURCE_URI);
        return el;
    }

    /**
     * Builds {@code <wsman:SelectorSet><wsman:Selector Name="X">value</wsman:Selector>…</wsman:SelectorSet>}
     * from the given name→value map. Returns {@code null} for an empty map (callers should
     * skip adding a header in that case).
     */
    public static Element selectorSet(Map<String, String> selectors) {
        if (selectors == null || selectors.isEmpty()) return null;
        Document doc = newDocument();
        Element set = doc.createElementNS(WSManConstants.XML_NS_DMTF_WSMAN_V1, "wsman:SelectorSet");
        for (Map.Entry<String, String> e : selectors.entrySet()) {
            Element sel = doc.createElementNS(WSManConstants.XML_NS_DMTF_WSMAN_V1, "wsman:Selector");
            sel.setAttribute("Name", e.getKey());
            sel.setTextContent(e.getValue());
            set.appendChild(sel);
        }
        return set;
    }

    /**
     * Convenience for the common single-selector case (e.g. ShellId or CommandId).
     */
    public static Element selectorSet(String name, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(name, value);
        return selectorSet(m);
    }

    /**
     * Builds {@code <wsman:OptionSet>} for the standard WinRS shell options:
     * {@code WINRS_NOPROFILE} and {@code WINRS_CODEPAGE}. Returns {@code null} if both
     * are at defaults that the server would already assume — but in practice we always
     * emit at least the code page to avoid surprises, so we always return a non-null
     * element when called.
     */
    public static Element optionSet(ShellOptions options) {
        Document doc = newDocument();
        Element set = doc.createElementNS(WSManConstants.XML_NS_DMTF_WSMAN_V1, "wsman:OptionSet");
        // WINRS_NOPROFILE: boolean, value "TRUE" or "FALSE" in the element text.
        Element noProfile = doc.createElementNS(WSManConstants.XML_NS_DMTF_WSMAN_V1, "wsman:Option");
        noProfile.setAttribute("Name", ShellConstants.OPT_NO_PROFILE);
        noProfile.setTextContent(options.isNoProfile() ? "TRUE" : "FALSE");
        set.appendChild(noProfile);
        // WINRS_CODEPAGE: integer, value in element text.
        Element codepage = doc.createElementNS(WSManConstants.XML_NS_DMTF_WSMAN_V1, "wsman:Option");
        codepage.setAttribute("Name", ShellConstants.OPT_CODEPAGE);
        codepage.setTextContent(Integer.toString(options.getCodepage()));
        set.appendChild(codepage);
        return set;
    }

    /**
     * Builds {@code <wsman:MaxEnvelopeSize mustUnderstand="true">N</wsman:MaxEnvelopeSize>}.
     * WinRM rejects shell Create requests that omit this header (the MS-WSMV reference
     * envelope marks it as {@code mustUnderstand}). The pywinrm default is 153600 bytes.
     */
    public static Element maxEnvelopeSize(long bytes) {
        Document doc = newDocument();
        Element el = doc.createElementNS(WSManConstants.XML_NS_DMTF_WSMAN_V1, "wsman:MaxEnvelopeSize");
        el.setAttributeNS("http://www.w3.org/2003/05/soap-envelope", "soap:mustUnderstand", "true");
        el.setTextContent(Long.toString(bytes));
        return el;
    }

    /**
     * Builds {@code <wsman:OperationTimeout>PT…S</wsman:OperationTimeout>}. Uses
     * {@link #xsdDuration(Duration)} to format the value the way pywinrm and the
     * MS-WSMV reference envelopes do.
     */
    public static Element operationTimeout(Duration timeout) {
        Document doc = newDocument();
        Element el = doc.createElementNS(WSManConstants.XML_NS_DMTF_WSMAN_V1, "wsman:OperationTimeout");
        el.setTextContent(xsdDuration(timeout));
        return el;
    }

    /**
     * Formats a {@link Duration} as an {@code xs:duration} string with millisecond
     * precision, e.g. {@code PT60.000S}. Matches pywinrm's wire format and the
     * MS-WSMV reference envelopes — used by both {@link #operationTimeout(Duration)}
     * and {@code <rsp:IdleTimeOut>} emission in {@code ShellBodyBuilder}.
     */
    static String xsdDuration(Duration d) {
        long millis = d.toMillis();
        return String.format("PT%d.%03dS", millis / 1000, millis % 1000);
    }

    /**
     * Builds {@code <wsman:Locale xml:lang="…" mustUnderstand="false"/>}. WinRM uses this
     * to pick the language of error messages; the element is required to be present even
     * when the value doesn't matter for the operation.
     */
    public static Element locale(String langTag) {
        Document doc = newDocument();
        Element el = doc.createElementNS(WSManConstants.XML_NS_DMTF_WSMAN_V1, "wsman:Locale");
        el.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:lang", langTag);
        el.setAttributeNS("http://www.w3.org/2003/05/soap-envelope", "soap:mustUnderstand", "false");
        return el;
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
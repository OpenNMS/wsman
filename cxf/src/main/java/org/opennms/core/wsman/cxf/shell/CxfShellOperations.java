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

import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.ws.Dispatch;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceFeature;
import javax.xml.ws.soap.SOAPBinding;

import org.apache.cxf.endpoint.Client;
import org.apache.cxf.headers.Header;
import org.apache.cxf.jaxws.DispatchImpl;
import org.apache.cxf.ws.addressing.AddressingProperties;
import org.apache.cxf.ws.addressing.AttributedURIType;
import org.apache.cxf.ws.addressing.EndpointReferenceType;
import org.apache.cxf.ws.addressing.JAXWSAConstants;
import org.apache.cxf.ws.addressing.WSAddressingFeature;
import org.apache.cxf.ws.addressing.soap.VersionTransformer;
import org.opennms.core.wsman.shell.ShellOptions;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Wraps a CXF {@link Dispatch} configured for the WinRS shell endpoint and exposes the
 * five WinRS operations (Create / Command / Receive / Signal / Delete) as plain methods
 * taking and returning DOM {@link Element}s.
 *
 * <p>The class is deliberately encryption-agnostic: it constructs the {@link Dispatch}
 * and exposes the underlying CXF {@link Client} via {@link #getClient()} so callers
 * (e.g. {@code CXFWSManClient}) can attach the same Kerberos-encryption / TLS / auth
 * interceptors and {@code HTTPConduit} configuration they use for the JAX-WS proxies.
 *
 * <p>Each operation builds the per-call WS-Addressing properties (Action, MessageID,
 * ReplyTo, To) and the WS-Man SOAP headers (ResourceURI + per-op SelectorSet / OptionSet)
 * before invoking the dispatch. The body element is supplied by the caller — typically
 * produced by {@link ShellBodyBuilder}.
 */
public class CxfShellOperations implements ShellOperations {

    private static final QName SHELL_SERVICE_QNAME = new QName(ShellConstants.NS_SHELL, "ShellService");
    private static final QName SHELL_PORT_QNAME    = new QName(ShellConstants.NS_SHELL, "ShellPort");

    // WinRM-required WS-Man header defaults. These match pywinrm's wire format and the
    // MS-WSMV §3.1.4.10 reference envelopes; servers reject Create-shell without them.
    private static final long DEFAULT_MAX_ENVELOPE_SIZE = 153600L;
    private static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_LOCALE = "en-US";

    private final String endpointUrl;
    private final Dispatch<Source> dispatch;
    private final Client client;

    public CxfShellOperations(String endpointUrl) {
        this.endpointUrl = endpointUrl;
        Service service = Service.create(SHELL_SERVICE_QNAME);
        service.addPort(SHELL_PORT_QNAME, SOAPBinding.SOAP12HTTP_BINDING, endpointUrl);
        // Enable WS-Addressing so the MAPCodec is installed in the outbound interceptor
        // chain — without it the wsa:Action / wsa:To / wsa:MessageID headers we set via
        // AddressingProperties aren't emitted and the WinRM server rejects the request
        // with "the request does not have all the expected SOAP headers".
        WebServiceFeature addressing = new WSAddressingFeature();
        this.dispatch = service.createDispatch(
            SHELL_PORT_QNAME, Source.class, Service.Mode.PAYLOAD, addressing);
        this.client = ((DispatchImpl<Source>) dispatch).getClient();
    }

    /**
     * Underlying CXF client — exposed so callers can attach interceptors (Kerberos
     * encryption, logging) and configure the {@code HTTPConduit} (TLS, basic auth,
     * timeouts) the same way they do for the JAX-WS proxies in {@code CXFWSManClient}.
     */
    public Client getClient() {
        return client;
    }

    /**
     * Send a Create-shell request. Returns the body {@link Element} of the response
     * (typically a {@code <rsp:Shell>}, or sometimes a {@code <wsa:ResourceCreated>});
     * pass it to {@link ShellResponseParser#extractShellId(Element)} to get the ShellId.
     */
    @Override
    public Element create(Element shellBody, ShellOptions options) {
        prepareRequest(ShellConstants.ACTION_CREATE, null, ShellSoapHeaders.optionSet(options));
        Source response = dispatch.invoke(new DOMSource(shellBody));
        return responseToElement(response);
    }

    /** Send a Command request against an existing shell. */
    @Override
    public Element command(String shellId, Element commandBody) {
        Element selectorSet = ShellSoapHeaders.selectorSet("ShellId", shellId);
        prepareRequest(ShellConstants.ACTION_COMMAND, selectorSet, null);
        Source response = dispatch.invoke(new DOMSource(commandBody));
        return responseToElement(response);
    }

    /**
     * Send a Receive request. Servers may block on this for several seconds while waiting
     * for output, so callers typically configure a generous {@code OperationTimeout} on
     * the HTTP layer and loop on this until {@code ReceiveChunk.isDone()} is true.
     */
    @Override
    public Element receive(String shellId, Element receiveBody) {
        Element selectorSet = ShellSoapHeaders.selectorSet("ShellId", shellId);
        prepareRequest(ShellConstants.ACTION_RECEIVE, selectorSet, null);
        Source response = dispatch.invoke(new DOMSource(receiveBody));
        return responseToElement(response);
    }

    /** Send a Signal (typically Terminate, to abort a running command). */
    @Override
    public Element signal(String shellId, Element signalBody) {
        Element selectorSet = ShellSoapHeaders.selectorSet("ShellId", shellId);
        prepareRequest(ShellConstants.ACTION_SIGNAL, selectorSet, null);
        Source response = dispatch.invoke(new DOMSource(signalBody));
        return responseToElement(response);
    }

    /** Send a Delete to destroy the shell. */
    @Override
    public void delete(String shellId) {
        Element selectorSet = ShellSoapHeaders.selectorSet("ShellId", shellId);
        prepareRequest(ShellConstants.ACTION_DELETE, selectorSet, null);
        // Delete has an empty SOAP body; JAX-WS Dispatch requires a Source even so.
        dispatch.invoke(new StreamSource(new StringReader("<empty/>")));
    }

    private void prepareRequest(String action, Element selectorSet, Element optionSet) {
        Map<String, Object> ctx = dispatch.getRequestContext();

        // WS-Addressing properties: Action + a fresh MessageID per request.
        AddressingProperties addressing = newAddressingProperties(action);
        ctx.put(JAXWSAConstants.CLIENT_ADDRESSING_PROPERTIES, addressing);

        // WS-Man SOAP headers: WinRM requires MaxEnvelopeSize, OperationTimeout, and
        // Locale on every shell request (it rejects Create-shell without them); plus
        // ResourceURI always, and optional SelectorSet / OptionSet.
        List<Header> headers = new ArrayList<>(6);
        headers.add(domHeader(ShellSoapHeaders.resourceUri()));
        headers.add(domHeader(ShellSoapHeaders.maxEnvelopeSize(DEFAULT_MAX_ENVELOPE_SIZE)));
        headers.add(domHeader(ShellSoapHeaders.operationTimeout(DEFAULT_OPERATION_TIMEOUT)));
        headers.add(domHeader(ShellSoapHeaders.locale(DEFAULT_LOCALE)));
        if (selectorSet != null) {
            headers.add(domHeader(selectorSet));
        }
        if (optionSet != null) {
            headers.add(domHeader(optionSet));
        }
        ctx.put(Header.HEADER_LIST, headers);
    }

    private AddressingProperties newAddressingProperties(String action) {
        AddressingProperties maps = new AddressingProperties();
        AttributedURIType actionUri = new AttributedURIType();
        actionUri.setValue(action);
        maps.setAction(actionUri);
        AttributedURIType messageId = new AttributedURIType();
        messageId.setValue("uuid:" + UUID.randomUUID());
        maps.setMessageID(messageId);
        AttributedURIType to = new AttributedURIType();
        to.setValue(endpointUrl);
        maps.setTo(to);
        // Use the 2004/08 anonymous address VALUE for ReplyTo/FaultTo to match the
        // existing JAX-WS proxies and pywinrm. For WSMAN_1_0 endpoints, CXFWSManClient
        // additionally installs a TransformOutInterceptor that rewrites the element
        // namespace from 2005/08 to 2004/08.
        AttributedURIType anonymous = new AttributedURIType();
        anonymous.setValue(VersionTransformer.Names200408.WSA_ANONYMOUS_ADDRESS);
        EndpointReferenceType epr = new EndpointReferenceType();
        epr.setAddress(anonymous);
        maps.setReplyTo(epr);
        maps.setFaultTo(epr);
        return maps;
    }

    private static Header domHeader(Element el) {
        QName name = new QName(el.getNamespaceURI(), el.getLocalName());
        return new Header(name, el);
    }

    /**
     * Materialise a JAX-WS {@link Source} response (payload, not full message) into a DOM
     * {@link Element}. Returns {@code null} if the body is empty (e.g. for {@code Delete}
     * or {@code SignalResponse} when the server omits a body).
     */
    private static Element responseToElement(Source response) {
        if (response == null) return null;
        if (response instanceof DOMSource) {
            Node node = ((DOMSource) response).getNode();
            if (node == null) return null;
            return node instanceof Element ? (Element) node
                : node.getOwnerDocument().getDocumentElement();
        }
        // Other Source types (StreamSource, SAXSource) — convert via Transformer.
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DOMResult result = new DOMResult(dbf.newDocumentBuilder().newDocument());
            TransformerFactory.newInstance().newTransformer().transform(response, result);
            Document doc = (Document) result.getNode();
            return doc == null ? null : doc.getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to materialise shell response Source to DOM", e);
        }
    }

}
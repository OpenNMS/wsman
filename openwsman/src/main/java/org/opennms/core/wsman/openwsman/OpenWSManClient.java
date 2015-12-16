/*
 * Copyright 2015, The OpenNMS Group
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
package org.opennms.core.wsman.openwsman;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.MimeHeaders;
import javax.xml.soap.SOAPBody;
import javax.xml.soap.SOAPConstants;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;

import org.opennms.core.wsman.WSManClient;
import org.opennms.core.wsman.WSManConstants;
import org.opennms.core.wsman.WSManEndpoint;
import org.opennms.core.wsman.WSManException;
import org.openwsman.Client;
import org.openwsman.ClientOptions;
import org.openwsman.Filter;
import org.openwsman.XmlDoc;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import org.openwsman.OpenWSManConstants;

/**
 * A WS-Man client implemented using OpenWSMan.
 *
 * OpenWSMan is a BSD 3 licensed WS-Man client/server implementation
 * written in C++. It provides a number of language specific
 * bindings, including those for Java, via SWIG.
 *
 * This class is not intended to be actually used, but rather
 * to serve as a reference implementation.
 *
 * @author jwhite
 */
public class OpenWSManClient implements WSManClient {

    private final WSManEndpoint m_endpoint;

    public OpenWSManClient(WSManEndpoint endpoint) {
        m_endpoint = Objects.requireNonNull(endpoint, "endpoint cannot be null");
    }

    private Client getClient() {
        URL url = m_endpoint.getUrl();
        
        Client client = new Client(url.getHost(), url.getPort(), url.getPath(), url.getProtocol(), m_endpoint.getUsername(), m_endpoint.getPassword());
        if (m_endpoint.isBasicAuth()) {
            client.transport().set_auth_method(OpenWSManConstants.BASIC_AUTH_STR);
        }

        if (!m_endpoint.isStrictSSL()) {
            // Disable SSL cert check
            client.transport().set_verify_host(0);
            client.transport().set_verify_peer(0);
        }

        return client;
    }

    private ClientOptions getClientOptions() {
        ClientOptions options = new ClientOptions();
        //options.set_dump_request();
        return options;
    }

    @Override
    public String enumerateWithFilter(String resourceUri, String dialect, String filter) {
        final Client client = getClient();
        final ClientOptions options = getClientOptions();

        Filter enumFilter = new Filter();
        if (WSManConstants.XML_NS_WQL_DIALECT.equals(dialect)) {
            enumFilter.wql(filter);
        } else {
            throw new WSManException("Unsupported dialect.");
        }

        XmlDoc result = client.enumerate(options, enumFilter, resourceUri);
        if ((result == null) || result.isFault()) {
            throw new WSManException("Enumeration failed: " + ((result != null) ? result.fault().reason() : "?"));
        } else {
            return result.context();
        }
    }

    @Override
    public String pull(String contextId, String resourceUri, List<Node> nodes, boolean recursive) {
        final Client client = getClient();
        final ClientOptions options = getClientOptions();

        XmlDoc result = client.pull(options, null, resourceUri, contextId);
        if ((result == null) || result.isFault()) {
            throw new WSManException("Pull failed: " + ((result != null) ? result.fault().reason() : "?"));
        } else {
            try {
                // Parse the SOAP response
                SOAPMessage message = getSoapMessage(result);
                
                // Now grab the items from the body
                SOAPBody body = message.getSOAPBody();
                NodeList returnList = body.getElementsByTagName("wsen:Items");

                // Re-encode the items so they are "disconnected" from the DOM tree
                for (int i = 0; i < returnList.getLength(); i++) {
                    String innerXml = innerXml(returnList.item(0)).trim();
                    Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                            .parse(new InputSource(new StringReader(innerXml)));
                    nodes.add(doc);
                }
                return "TODO";
            } catch (IOException | SOAPException | SAXException | ParserConfigurationException e) {
                throw new WSManException("Failed to parse OpenWSMan's XML output.", e);
            }
        }
    }

    @Override
    public String enumerateAndPullUsingFilter(String resourceUri, String dialect, String filter, List<Node> nodes, boolean recursive) {
        return pull(enumerateWithFilter(resourceUri, dialect, filter), resourceUri, nodes, recursive);
    }

    @Override
    public Node get(String resourceUri, Map<String, String> selectors) {
        final Client client = getClient();
        final ClientOptions options = getClientOptions();

        // Copy the selectors to the client options
        selectors.entrySet().stream()
            .forEach(e -> options.add_selector(e.getKey(), e.getValue()));

        XmlDoc result = client.get(options, resourceUri);
        if ((result == null) || result.isFault()) {
            throw new WSManException("Get failed: " + ((result != null) ? result.fault().reason() : "?"));
        } else {
            try {
                // Parse the SOAP response
                SOAPMessage message = getSoapMessage(result);

                // Now grab the items from the body
                SOAPBody body = message.getSOAPBody();

                // Re-encode it so that it's 'disconnected' from the XML tree
                String innerXml = innerXml(body).trim();
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(new InputSource(new StringReader(innerXml)));
                return doc;
            } catch (IOException | SOAPException | SAXException | ParserConfigurationException e) {
                throw new WSManException("Failed to parse OpenWSMan's XML output.", e);
            }
        }
    }

    public static SOAPMessage getSoapMessage(XmlDoc doc) throws IOException, SOAPException {
        // We need to return the node from the SOAP message body
        // OpenWSMan gives us the whole SOAP response in their own XML wrappers
        // so we re-encode it, and parse it out again
        String xml = doc.encode("UTF-8").trim();
        // Parse the SOAP response
        MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
        return factory.createMessage(
                new MimeHeaders(),
                new ByteArrayInputStream(xml.getBytes(Charset
                        .forName("UTF-8"))));
    }

    public static String innerXml(Node node) {
        DOMImplementationLS lsImpl = (DOMImplementationLS)node.getOwnerDocument().getImplementation().getFeature("LS", "3.0");
        LSSerializer lsSerializer = lsImpl.createLSSerializer();
        NodeList childNodes = node.getChildNodes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < childNodes.getLength(); i++) {
           sb.append(lsSerializer.writeToString(childNodes.item(i)));
        }
        return sb.toString(); 
    }

    @Override
    public String enumerate(String resourceUri) {
        throw new WSManException("Unsupported.");
    }

    @Override
    public String enumerateAndPull(String resourceUri, List<Node> nodes, boolean recursive) {
        throw new WSManException("Unsupported.");
    }
}

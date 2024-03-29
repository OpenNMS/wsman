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
package org.opennms.core.wsman;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.opennms.core.wsman.exceptions.InvalidResourceURI;
import org.opennms.core.wsman.exceptions.UnauthorizedException;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.responsetemplating.ResponseTemplateTransformer;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mycila.xmltool.XMLDoc;
import com.mycila.xmltool.XMLTag;

/**
 * This test connects to a local HTTP server provided
 * by WireMock that returns static content.
 *
 * Used to verify the generated requests and validate response parsing.
 *
 * @author jwhite
 */
public abstract class AbstractWSManClientIT {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(WireMockConfiguration.wireMockConfig()
            .withRootDirectory(Paths.get("..", "itests", "src", "main", "resources").toString())
            .extensions(new ResponseTemplateTransformer(true))
            .dynamicPort());

    private WSManClient client;

    public abstract WSManClientFactory getFactory();

    @BeforeClass
    public static void setupClass() {
        //System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE");
    }

    @Before
    public void setUp() throws MalformedURLException {
        WSManEndpoint endpoint = new WSManEndpoint.Builder(String.format("http://127.0.0.1:%d/wsman", wireMockRule.port()))
                .withServerVersion(WSManVersion.WSMAN_1_0)
                .build();
        client = getFactory().getClient(endpoint);
    }

    @Test
    public void canIdentify() throws InterruptedException {
        stubFor(post(urlEqualTo("/wsman"))
                .willReturn(aResponse()
                    .withHeader("Content-Type", "Content-Type: application/soap+xml; charset=utf-8")
                    .withBodyFile("identify-response.xml")));

        Identity identifyResponse = client.identify();

        dumpRequestsToStdout();

        assertEquals(1, identifyResponse.getProtocolVersions().size());
        assertEquals("http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd", identifyResponse.getProtocolVersions().get(0));
        assertEquals("Openwsman Project", identifyResponse.getProductVendor());
        assertEquals("2.0.0", identifyResponse.getProductVersion());
    }

    @Test
    public void canEnumerateWithWQLFilter() throws InterruptedException {
        stubFor(post(urlEqualTo("/wsman"))
                .willReturn(aResponse()
                    .withHeader("Content-Type", "Content-Type: application/soap+xml; charset=utf-8")
                    .withBodyFile("enum-response.xml")));

        String contextId = client.enumerateWithFilter(
                WSManConstants.CIM_ALL_AVAILABLE_CLASSES,
                WSManConstants.XML_NS_WQL_DIALECT,
                "select DeviceDescription,PrimaryStatus,TotalOutputPower,InputVoltage,Range1MaxInputPower,FirmwareVersion,RedundancyStatus from DCIM_PowerSupplyView where DetailedState != 'Absent' and PrimaryStatus != 0");

        dumpRequestsToStdout();

        assertEquals("c6595ee1-2664-1664-801f-c115cfb5fe14", contextId);
    }

    @Test
    public void canPull() throws InterruptedException {
        stubFor(post(urlEqualTo("/wsman"))
                .willReturn(aResponse()
                    .withHeader("Content-Type", "Content-Type: application/soap+xml; charset=utf-8")
                    .withBodyFile("pull-response.xml")));

        List<Node> nodes = new ArrayList<>();
        client.pull("c6595ee1-2664-1664-801f-c115cfb5fe14", WSManConstants.CIM_ALL_AVAILABLE_CLASSES, nodes, false);

        dumpRequestsToStdout();

        assertEquals(1, nodes.size());

        XMLTag tag = XMLDoc.from(nodes.get(0), true);
        int inputVoltage = Integer.valueOf(tag.gotoChild("n1:InputVoltage").getText());
        assertEquals(120, inputVoltage);
    }

    @Test
    public void canPullRecursively() throws InterruptedException {
        stubFor(post(urlEqualTo("/wsman")).inScenario("Recursive pull")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                    .withHeader("Content-Type", "Content-Type: application/soap+xml; charset=utf-8")
                    .withBodyFile("recursive-pull-response-1.xml"))
                .willSetStateTo("Pull #2"));

        stubFor(post(urlEqualTo("/wsman")).inScenario("Recursive pull")
                .whenScenarioStateIs("Pull #2")
                .willReturn(aResponse()
                    .withHeader("Content-Type", "Content-Type: application/soap+xml; charset=utf-8")
                    .withBodyFile("recursive-pull-response-2.xml")));

        List<Node> nodes = new ArrayList<>();
        client.pull("c6595ee1-2664-1664-801f-c115cfb5fe14", WSManConstants.CIM_ALL_AVAILABLE_CLASSES, nodes, true);

        dumpRequestsToStdout();

        assertEquals(2, nodes.size());

        XMLTag tag = XMLDoc.from(nodes.get(0), true);
        int inputVoltage = Integer.valueOf(tag.gotoChild("n1:InputVoltage").getText());
        assertEquals(120, inputVoltage);

        tag = XMLDoc.from(nodes.get(1), true);
        inputVoltage = Integer.valueOf(tag.gotoChild("n1:InputVoltage").getText());
        assertEquals(121, inputVoltage);
    }

    @Test
    public void canEnumerateAndPullUsingWQLFilter() throws InterruptedException {
        stubFor(post(urlEqualTo("/wsman"))
                .willReturn(aResponse()
                    .withHeader("Content-Type", "Content-Type: application/soap+xml; charset=utf-8")
                    .withBodyFile("optimized-enum-response.xml")));

        List<Node> nodes = new ArrayList<>();
        client.enumerateAndPullUsingFilter(WSManConstants.CIM_ALL_AVAILABLE_CLASSES,
                "select DeviceDescription,PrimaryStatus,TotalOutputPower,InputVoltage,Range1MaxInputPower,FirmwareVersion,RedundancyStatus from DCIM_PowerSupplyView where DetailedState != 'Absent' and PrimaryStatus != 0",
                WSManConstants.XML_NS_WQL_DIALECT,
                nodes,
                false);

        dumpRequestsToStdout();

        assertEquals(1, nodes.size());
        Map<String, String> props = toMap(nodes.get(0));
        assertEquals(Integer.valueOf(120), Integer.valueOf(props.get("InputVoltage")));
    }

    @Test
    public void canEnumerateAndPullFragmentUsingWQLFilter() throws InterruptedException {
        stubFor(post(urlEqualTo("/wsman")).inScenario("Enum and pull fragments")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                    .withHeader("Content-Type", "Content-Type: application/soap+xml; charset=utf-8")
                    .withBodyFile("optimized-enum-response-with-fragments-1.xml"))
                .willSetStateTo("Pull"));

        stubFor(post(urlEqualTo("/wsman")).inScenario("Enum and pull fragments")
                .whenScenarioStateIs("Pull")
                .willReturn(aResponse()
                    .withHeader("Content-Type", "Content-Type: application/soap+xml; charset=utf-8")
                    .withBodyFile("optimized-enum-response-with-fragments-2.xml")));

        List<Node> nodes = new ArrayList<>();
        client.enumerateAndPullUsingFilter("http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/*",
                "select Name, Size, FreeSpace FROM Win32_LogicalDisk",
                WSManConstants.XML_NS_WQL_DIALECT,
                nodes,
                false);

        dumpRequestsToStdout();

        assertEquals(4, nodes.size());
        Map<String, String> props = toMap(nodes.get(0));
        assertEquals("A:", props.get("Name"));
        assertEquals("", props.get("Size"));

        props = toMap(nodes.get(2));
        assertEquals("D:", props.get("Name"));
        assertEquals("64421359616", props.get("Size"));
    }

    private static Map<String, String> toMap(Node node) {
        Map<String, String> map = new HashMap<>();
        // Parse the values from the child nodes
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);

            if (child.getLocalName() == null || child.getTextContent() == null) {
                continue;
            }

            map.put(child.getLocalName(), child.getTextContent());
        }
        return map;
    }

    @Test
    public void canGet() throws FileNotFoundException, IOException {
        stubFor(post(urlEqualTo("/wsman"))
                .willReturn(aResponse()
                    .withHeader("Content-Type", "Content-Type: application/soap+xml; charset=utf-8")
                    .withBodyFile("get-response.xml")));

        Map<String, String> selectors = new HashMap<>();
        selectors.put("CreationClassName", "DCIM_ComputerSystem");
        selectors.put("Name", "srv:system");
        Node node = client.get("http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_ComputerSystem", selectors);

        dumpRequestsToStdout();

        assertNotNull(node);

        XMLTag tag = XMLDoc.from(node, true);
        int primaryStatus = Integer.valueOf(tag.gotoChild("n1:PrimaryStatus").getText());
        assertEquals(1, primaryStatus);
    }

    @Test(expected=UnauthorizedException.class)
    public void throwsUnauthorizedExceptionOn401() {
        stubFor(post(urlEqualTo("/wsman"))
                .willReturn(
                        aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Not allowed!")));
        client.enumerate("http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_ComputerSystem");
    }

    @Test(expected=InvalidResourceURI.class)
    public void throwsInvalidResourceURIOnFault() {
        stubFor(post(urlEqualTo("/wsman"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "Content-Type: application/soap+xml; charset=utf-8")
                        .withBodyFile("get-response-fault.xml")));
        Map<String, String> selectors = new HashMap<>();
        selectors.put("CreationClassName", "DCIM_ComputerSystem");
        selectors.put("Name", "srv:system");
        client.get("http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_ComputerSystem", selectors);
    }

    private void dumpRequestsToStdout() {
        findAll(postRequestedFor(urlMatching("/.*"))).forEach(r -> System.out.println(prettyFormat(r.getBodyAsString(), 4)));
    }

    public static String prettyFormat(String input, int indent) {
        try {
            Source xmlInput = new StreamSource(new StringReader(input));
            StringWriter stringWriter = new StringWriter();
            StreamResult xmlOutput = new StreamResult(stringWriter);
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setAttribute("indent-number", indent);
            Transformer transformer = transformerFactory.newTransformer(); 
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(xmlInput, xmlOutput);
            return xmlOutput.getWriter().toString();
        } catch (Exception e) {
            throw new RuntimeException(e); // simple exception handling, please review it
        }
    }
}

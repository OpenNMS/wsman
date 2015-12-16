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
package org.opennms.core.wsman;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opennms.core.wsman.WSManClientFactory;
import org.opennms.core.wsman.WSManEndpoint;
import org.opennms.core.wsman.utils.TestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import com.mycila.xmltool.XMLDoc;
import com.mycila.xmltool.XMLTag;

import wiremock.com.google.common.collect.Lists;
import wiremock.com.google.common.collect.Maps;

/**
 * This test connects to an iDrac device using the properties
 * stored in ~/wsman.properties.
 *
 * @author jwhite
 */
public abstract class AbstractWSManClientDracIT {
    private final static Logger LOG = LoggerFactory.getLogger(AbstractWSManClientDracIT.class);

    private WSManClient client;

    public abstract WSManClientFactory getFactory();

    @BeforeClass
    public static void setupClass() {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE");
    }

    @Before
    public void setUp() throws IOException {
        WSManEndpoint endpoint = TestUtils.getEndpointFromLocalConfiguration();
        LOG.info("Using endpoint: {}", endpoint);
        client = getFactory().getClient(endpoint);
    }

    @Test
    public void canGetInputVoltage() {
        List<Node> powerSupplies = Lists.newLinkedList();
        client.enumerateAndPullUsingFilter(
                WSManConstants.CIM_ALL_AVAILABLE_CLASSES,
                WSManConstants.XML_NS_WQL_DIALECT,
                "select DeviceDescription,PrimaryStatus,TotalOutputPower,InputVoltage,Range1MaxInputPower,FirmwareVersion,RedundancyStatus from DCIM_PowerSupplyView where DetailedState != 'Absent' and PrimaryStatus != 0",
                powerSupplies,
                true);
        assertEquals(1, powerSupplies.size());

        XMLTag tag = XMLDoc.from(powerSupplies.get(0), true);
        int inputVoltage = Integer.valueOf(tag.gotoChild("n1:InputVoltage").getText());
        assertEquals(120, inputVoltage);
    }

    @Test
    public void canGetSystemPrimaryStatus() throws FileNotFoundException, IOException {
        Map<String, String> selectors = Maps.newHashMap();
        selectors.put("CreationClassName", "DCIM_ComputerSystem");
        selectors.put("Name", "srv:system");
        Node node = client.get("http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_ComputerSystem", selectors);
        assertNotNull(node);

        assertEquals("DCIM_ComputerSystem", node.getLocalName());
        assertEquals("http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_ComputerSystem", node.getNamespaceURI());

        XMLTag tag = XMLDoc.from(node, true);
        System.err.println(tag.getCurrentTagName());
        System.err.println(tag);
        int primaryStatus = Integer.valueOf(tag.gotoChild("n1:PrimaryStatus").getText());
        assertEquals(1, primaryStatus);
    }
}

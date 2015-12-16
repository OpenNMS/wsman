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

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opennms.core.wsman.WSManClientFactory;
import org.opennms.core.wsman.WSManEndpoint;
import org.opennms.core.wsman.utils.TestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import wiremock.com.google.common.collect.Lists;
/**
 * This test connects to an instance of Windows Servier 2008
 * using the properties stored in ~/wsman.properties.
 *
 * @author jwhite
 */
public abstract class AbstractWSManClientWinServer2008IT {
    private final static Logger LOG = LoggerFactory.getLogger(AbstractWSManClientWinServer2008IT.class);

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
    public void canEnumerateWin32Services() {
        List<Node> services = Lists.newArrayList();
        client.enumerateAndPull("http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/Win32_Service", services, true);
        assertTrue(services.size() + " services", services.size() > 10);
    }
}

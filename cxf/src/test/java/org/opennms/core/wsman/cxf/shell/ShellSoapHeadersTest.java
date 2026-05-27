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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;
import org.opennms.core.wsman.WSManConstants;
import org.opennms.core.wsman.shell.ShellOptions;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class ShellSoapHeadersTest {

    private static final String WSMAN_NS = WSManConstants.XML_NS_DMTF_WSMAN_V1;

    // --- ResourceURI -----------------------------------------------------------------

    @Test
    public void resourceUri_pointsAtWinrsCmdShell() {
        Element el = ShellSoapHeaders.resourceUri();

        assertEquals(WSMAN_NS, el.getNamespaceURI());
        assertEquals("ResourceURI", el.getLocalName());
        assertEquals(ShellConstants.SHELL_RESOURCE_URI, el.getTextContent());
    }

    // --- SelectorSet -----------------------------------------------------------------

    @Test
    public void selectorSet_singleSelector_buildsOneSelectorElement() {
        Element set = ShellSoapHeaders.selectorSet("ShellId", "uuid:ABC-123");

        assertNotNull(set);
        assertEquals(WSMAN_NS, set.getNamespaceURI());
        assertEquals("SelectorSet", set.getLocalName());

        NodeList selectors = set.getElementsByTagNameNS(WSMAN_NS, "Selector");
        assertEquals(1, selectors.getLength());
        Element sel = (Element) selectors.item(0);
        assertEquals("ShellId", sel.getAttribute("Name"));
        assertEquals("uuid:ABC-123", sel.getTextContent());
    }

    @Test
    public void selectorSet_multipleSelectors_preservesInsertionOrder() {
        Map<String, String> selectors = new LinkedHashMap<>();
        selectors.put("ShellId", "uuid:SHELL");
        selectors.put("CommandId", "uuid:CMD");

        Element set = ShellSoapHeaders.selectorSet(selectors);
        assertNotNull(set);

        NodeList items = set.getElementsByTagNameNS(WSMAN_NS, "Selector");
        assertEquals(2, items.getLength());

        Element first = (Element) items.item(0);
        assertEquals("ShellId", first.getAttribute("Name"));
        assertEquals("uuid:SHELL", first.getTextContent());

        Element second = (Element) items.item(1);
        assertEquals("CommandId", second.getAttribute("Name"));
        assertEquals("uuid:CMD", second.getTextContent());
    }

    @Test
    public void selectorSet_emptyMap_returnsNull() {
        assertNull(ShellSoapHeaders.selectorSet(Collections.emptyMap()));
    }

    @Test
    public void selectorSet_nullMap_returnsNull() {
        assertNull(ShellSoapHeaders.selectorSet((Map<String, String>) null));
    }

    // --- OptionSet -------------------------------------------------------------------

    @Test
    public void optionSet_defaults_emitsNoProfileTrueAndUtf8Codepage() {
        Element set = ShellSoapHeaders.optionSet(ShellOptions.defaults());

        assertEquals(WSMAN_NS, set.getNamespaceURI());
        assertEquals("OptionSet", set.getLocalName());

        NodeList options = set.getElementsByTagNameNS(WSMAN_NS, "Option");
        assertEquals(2, options.getLength());

        Element noProfile = optionByName(set, ShellConstants.OPT_NO_PROFILE);
        assertEquals("TRUE", noProfile.getTextContent());

        Element codepage = optionByName(set, ShellConstants.OPT_CODEPAGE);
        assertEquals("65001", codepage.getTextContent());
    }

    @Test
    public void optionSet_noProfileFalse_emitsFalseLiteral() {
        ShellOptions opts = new ShellOptions.Builder()
            .withNoProfile(false)
            .build();

        Element set = ShellSoapHeaders.optionSet(opts);

        assertEquals("FALSE", optionByName(set, ShellConstants.OPT_NO_PROFILE).getTextContent());
    }

    @Test
    public void optionSet_customCodepage() {
        ShellOptions opts = new ShellOptions.Builder()
            .withCodepage(437) // OEM US English, the classic cmd.exe default
            .build();

        Element set = ShellSoapHeaders.optionSet(opts);

        assertEquals("437", optionByName(set, ShellConstants.OPT_CODEPAGE).getTextContent());
    }

    // --- helpers ---------------------------------------------------------------------

    private static Element optionByName(Element optionSet, String name) {
        NodeList options = optionSet.getElementsByTagNameNS(WSMAN_NS, "Option");
        for (int i = 0; i < options.getLength(); i++) {
            Element o = (Element) options.item(i);
            if (name.equals(o.getAttribute("Name"))) return o;
        }
        throw new AssertionError("Option " + name + " not found in OptionSet");
    }
}
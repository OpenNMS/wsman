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

/**
 * Constants, mostly XML namespaces, used by the implementations.
 *
 * @author jwhite
 */
public class WSManConstants {

    public static final String XML_NS_DMTF_WSMAN_V1 = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";

    public static final String XML_NS_WS_2004_08_ADDRESSING = "http://schemas.xmlsoap.org/ws/2004/08/addressing";

    public static final String XML_NS_WS_2004_09_ENUMERATION = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";

    public static final String XML_NS_WS_2004_09_TRANSFER = "http://schemas.xmlsoap.org/ws/2004/09/transfer";

    public static final String XML_NS_WQL_DIALECT =  "http://schemas.microsoft.com/wbem/wsman/1/WQL";

    public static final String CIM_ALL_AVAILABLE_CLASSES = "http://schemas.dmtf.org/wbem/wscim/1/*";
}

/*
 * Copyright 2016, The OpenNMS Group
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
package org.opennms.core.wsman.cxf;

import java.util.List;
import java.util.Objects;

import org.opennms.core.wsman.IdentifyResponse;

import schemas.dmtf.org.wbem.wsman.v1.IdentifyResponseType;

public class IdentifyResponseWrapper implements IdentifyResponse {

    private final IdentifyResponseType m_identifyResponse;

    public IdentifyResponseWrapper(IdentifyResponseType identifyResponse) {
        m_identifyResponse = Objects.requireNonNull(identifyResponse, "identifyResponse argument");
    }

    @Override
    public List<String> getProtocolVersions() {
        return m_identifyResponse.getProtocolVersion();
    }

    @Override
    public String getProductVendor() {
        return m_identifyResponse.getProductVendor();
    }

    @Override
    public String getProductVersion() {
        return m_identifyResponse.getProductVersion();
    }
}

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

import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
import jakarta.xml.bind.annotation.XmlSeeAlso;
import jakarta.xml.ws.soap.Addressing;

import schemas.dmtf.org.wbem.wsman.v1.IdentifyResponseType;
import schemas.dmtf.org.wbem.wsman.v1.IdentifyType;

/**
 * Used to discover the server's capabilities.
 *
 * @author jwhite
 */
@WebService
@XmlSeeAlso({schemas.dmtf.org.wbem.wsman.v1.ObjectFactory.class})
@SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.BARE)
@Addressing(required = false, enabled = false)
public interface IdentifyOperations {

    @WebMethod(operationName = "Identify")
    @WebResult(name = "IdentifyResponse", targetNamespace = "http://schemas.dmtf.org/wbem/wsman/identity/1/wsmanidentity.xsd", partName = "Body")
    public IdentifyResponseType identify(
            @WebParam(partName = "Identify", name = "Identify", targetNamespace = "http://schemas.dmtf.org/wbem/wsman/identity/1/wsmanidentity.xsd")
            IdentifyType identify
            );
}

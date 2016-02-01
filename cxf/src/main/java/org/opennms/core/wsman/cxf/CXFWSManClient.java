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
package org.opennms.core.wsman.cxf;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;

import org.apache.cxf.binding.soap.SoapBindingConstants;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.apache.cxf.interceptor.transform.TransformInInterceptor;
import org.apache.cxf.interceptor.transform.TransformOutInterceptor;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.message.Message;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transport.http.auth.DefaultBasicAuthSupplier;
import org.apache.cxf.transport.http.auth.HttpAuthHeader;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.ws.addressing.AddressingProperties;
import org.apache.cxf.ws.addressing.AttributedURIType;
import org.apache.cxf.ws.addressing.EndpointReferenceType;
import org.apache.cxf.ws.addressing.JAXWSAConstants;
import org.apache.cxf.ws.addressing.WSAddressingFeature;
import org.apache.cxf.ws.addressing.WSAddressingFeature.AddressingResponses;
import org.apache.cxf.ws.addressing.soap.VersionTransformer;
import org.opennms.core.wsman.Identity;
import org.opennms.core.wsman.WSManClient;
import org.opennms.core.wsman.WSManConstants;
import org.opennms.core.wsman.WSManEndpoint;
import org.opennms.core.wsman.WSManVersion;
import org.opennms.core.wsman.exceptions.HTTPException;
import org.opennms.core.wsman.exceptions.InvalidResourceURI;
import org.opennms.core.wsman.exceptions.SOAPFault;
import org.opennms.core.wsman.exceptions.UnauthorizedException;
import org.opennms.core.wsman.exceptions.WSManException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.xmlsoap.schemas.ws._2004._09.enumeration.Enumerate;
import org.xmlsoap.schemas.ws._2004._09.enumeration.EnumerateResponse;
import org.xmlsoap.schemas.ws._2004._09.enumeration.EnumerationContextType;
import org.xmlsoap.schemas.ws._2004._09.enumeration.FilterType;
import org.xmlsoap.schemas.ws._2004._09.enumeration.Pull;
import org.xmlsoap.schemas.ws._2004._09.enumeration.PullResponse;
import org.xmlsoap.schemas.ws._2004._09.transfer.TransferElement;

import com.google.common.collect.Maps;

import schemas.dmtf.org.wbem.wsman.v1.AttributableEmpty;
import schemas.dmtf.org.wbem.wsman.v1.AttributablePositiveInteger;
import schemas.dmtf.org.wbem.wsman.v1.IdentifyType;
import schemas.dmtf.org.wbem.wsman.v1.MaxEnvelopeSizeType;

/**
 * A WS-Man client implemented using JAX-WS &amp; CXF.
 *
 * @author jwhite
 */
public class CXFWSManClient implements WSManClient {
    private static final Logger LOG = LoggerFactory.getLogger(CXFWSManClient.class);
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String MEDIA_TYPE_SOAP_UTF8 = "application/soap+xml;charset=UTF-8";
    private static final org.apache.cxf.ws.addressing.ObjectFactory WSA_OBJECT_FACTORY = new org.apache.cxf.ws.addressing.ObjectFactory();
    private static final schemas.dmtf.org.wbem.wsman.v1.ObjectFactory WSMAN_OBJECT_FACTORY = new schemas.dmtf.org.wbem.wsman.v1.ObjectFactory();

    private final WSManEndpoint m_endpoint;

    public CXFWSManClient(WSManEndpoint endpoint) {
        m_endpoint = Objects.requireNonNull(endpoint, "endpoint cannot be null");
    }

    public IdentifyOperations getIdentifier() {
        // Create the proxy
        IdentifyOperations identifier = createProxyFor(IdentifyOperations.class, Maps.newHashMap(), Maps.newHashMap());
        Client cxfClient = ClientProxy.getClient(identifier);

        // The Identify command does not require any headers but Windows Server 2008
        // fails if the SOAP Envelope Header section is missing. In order to work around this
        // we add the ResourceURI header, which should be ignored.
        WSManHeaderInterceptor interceptor = new WSManHeaderInterceptor(WSManConstants.CIM_ALL_AVAILABLE_CLASSES);
        cxfClient.getOutInterceptors().add(interceptor);

        return identifier;
    }

    public EnumerationOperations getEnumerator(String resourceUri) {
        // Relocate the Filter element to the WS-Man namespace.
        // Our WSDLs generate it one package but the servers expect it to be in the other
        Map<String, String> outTransformMap = Maps.newHashMap();
        outTransformMap.put("{" + WSManConstants.XML_NS_WS_2004_09_ENUMERATION + "}Filter",
                "{" + WSManConstants.XML_NS_DMTF_WSMAN_V1 + "}Filter");

        // Create the proxy
        EnumerationOperations enumerator = createProxyFor(EnumerationOperations.class, outTransformMap, Maps.newHashMap());
        Client cxfClient = ClientProxy.getClient(enumerator);

        // Add the WS-Man ResourceURI to the SOAP header
        WSManHeaderInterceptor interceptor = new WSManHeaderInterceptor(resourceUri);
        cxfClient.getOutInterceptors().add(interceptor);

        return enumerator;
    }

    public TransferOperations getTransferer(String resourceUri, String elementType, Map<String, String> selectors) {
        // Modify the incoming response to use a generic element instead of the one provided
        // The JAX-WS implementation excepts the element types to match those in specified
        // by the annotated interface, but these are subject to change for every call we make
        Map<String, String> inTransformMap = Maps.newHashMap();
        inTransformMap.put(String.format("{%s}%s", resourceUri, elementType),
                "{" + WSManConstants.XML_NS_WS_2004_09_TRANSFER + "}TransferElement");

        // Create the proxy
        TransferOperations transferer = createProxyFor(TransferOperations.class, Maps.newHashMap(), inTransformMap);
        Client cxfClient = ClientProxy.getClient(transferer);

        // Add the WS-Man ResourceURI and SelectorSet to the SOAP header
        WSManHeaderInterceptor interceptor = new WSManHeaderInterceptor(resourceUri, selectors);
        cxfClient.getOutInterceptors().add(interceptor);

        return transferer;
    }

    @Override
    public Identity identify() {
        try {
            return new IdentifyResponseWrapper(getIdentifier().identify(new IdentifyType()));
        } catch (RuntimeException e) {
            throw wrapException(e);
        }
    }

    private EnumerateResponse enumerate(String resourceUri, String dialect, String filter, boolean optimized) {
        // Create the enumeration request
        Enumerate enumerate = new Enumerate();

        // If a filter was set, then add it to the request
        if (dialect != null && filter != null) {
            FilterType filterType = new FilterType();
            filterType.setDialect(dialect);
            filterType.getContent().add(filter);
            enumerate.setFilter(filterType);
        }

        // Optionally add the optimize enumeration element to the request
        if (optimized) {
            // Request an optimized response
            JAXBElement<AttributableEmpty> optimizeEnumeration = WSMAN_OBJECT_FACTORY.createOptimizeEnumeration(new AttributableEmpty());
            enumerate.getAny().add(optimizeEnumeration);
        }

        // Optionally specify the max envelope size
        if (m_endpoint.getMaxEnvelopeSize() != null) {
            MaxEnvelopeSizeType maxEnvelopeSizeValue = WSMAN_OBJECT_FACTORY.createMaxEnvelopeSizeType();
            maxEnvelopeSizeValue.setValue(BigInteger.valueOf(m_endpoint.getMaxEnvelopeSize()));
            JAXBElement<MaxEnvelopeSizeType> maxEnvelopeSize = WSMAN_OBJECT_FACTORY.createMaxEnvelopeSize(maxEnvelopeSizeValue);
            enumerate.getAny().add(maxEnvelopeSize);
        }

        // Optionally specify the maximum number of elements to return
        if (m_endpoint.getMaxElements() != null) {
            AttributablePositiveInteger maxElementsValue = new AttributablePositiveInteger();
            maxElementsValue.setValue(BigInteger.valueOf(m_endpoint.getMaxElements()));
            JAXBElement<AttributablePositiveInteger> maxElements = WSMAN_OBJECT_FACTORY.createMaxElements(maxElementsValue);
            enumerate.getAny().add(maxElements);
        }

        try {
            return getEnumerator(resourceUri).enumerate(enumerate);
        } catch (RuntimeException e) {
            throw wrapException(e);
        }
    }

    private String enumerateAndPull(String resourceUri, String dialect, String filter, List<Node> nodes, boolean recursive) {
        EnumerateResponse response = enumerate(resourceUri, dialect, filter, true);
        if (response == null) {
            throw new WSManException("Enumeration failed. See logs for details.");
        }

        String nextContextId = TypeUtils.getContextIdFrom(response);
        boolean endOfSequence = TypeUtils.getItemsFrom(response, nodes);

        if (!endOfSequence) {
            return pull(TypeUtils.getContextIdFrom(response), resourceUri, nodes, recursive);
        }
        return nextContextId;
    }

    @Override
    public String enumerate(String resourceUri) {
        EnumerateResponse response = enumerate(resourceUri, null, null, false);
        if (response == null) {
            throw new WSManException("Enumeration failed. See logs for details.");
        }
        return TypeUtils.getContextIdFrom(response);
    }

    @Override
    public String enumerateWithFilter(String resourceUri, String dialect, String filter) {
        EnumerateResponse response = enumerate(resourceUri, dialect, filter, false);
        if (response == null) {
            throw new WSManException("Enumeration failed. See logs for details.");
        }
        return TypeUtils.getContextIdFrom(response);
    }

    @Override
    public String pull(String contextId, String resourceUri, List<Node> nodes, boolean recursive) {
        // Create the pull request
        Pull pull = new Pull();

        // Add the context id to the request
        EnumerationContextType enumContext = new EnumerationContextType();
        enumContext.getContent().add(contextId);
        pull.setEnumerationContext(enumContext);

        // Optionally specify the maximum number of elements to return
        if (m_endpoint.getMaxElements() != null) {
            pull.setMaxElements(BigInteger.valueOf(m_endpoint.getMaxElements()));
        }

        // Issue the pull
        PullResponse response = null;
        try {
            response = getEnumerator(resourceUri).pull(pull);
        } catch (RuntimeException e) {
            throw wrapException(e);
        }
        if (response == null) {
            throw new WSManException(String.format("Pull failed for context id: %s. See logs for details.", contextId));
        }

        // Collect the results
        boolean endOfSequence = TypeUtils.getItemsFrom(response, nodes);
        String nextContextId = TypeUtils.getContextIdFrom(response);

        // If we're pulling recursively, and we haven't hit the last element, continue pulling
        if (recursive && !endOfSequence) {
            return pull(nextContextId, resourceUri, nodes, recursive);
        }

        return nextContextId;
    }

    @Override
    public String enumerateAndPull(String resourceUri, List<Node> nodes, boolean recursive) {
        return enumerateAndPull(resourceUri, null, null, nodes, recursive);
    }

    @Override
    public String enumerateAndPullUsingFilter(String resourceUri, String dialect, String filter, List<Node> nodes, boolean recursive) {
        return enumerateAndPull(resourceUri, dialect, filter, nodes, recursive);
    }

    @Override
    public Node get(String resourceUri, Map<String, String> selectors) {
        String elementType = TypeUtils.getElementTypeFromResourceUri(resourceUri);
        TransferOperations transferer = getTransferer(resourceUri, elementType, selectors);
        TransferElement transferElement = null;
        try {
            transferElement = transferer.get();
        } catch (RuntimeException e) {
            throw wrapException(e);
        }
        if (transferElement == null) {
            // Note that fault should be thrown if the object doesn't exist
            throw new WSManException("Get failed. See logs for details.");
        }
        return TypeUtils.transferElementToNode(transferElement, resourceUri, elementType);
    }

    /**
     * Creates a proxy service for the given JAX-WS annotated interface.
     */
    private <ProxyServiceType> ProxyServiceType createProxyFor(Class<ProxyServiceType> serviceClass,
            Map<String, String> outTransformMap, Map<String, String> inTransformMap) {
        // Setup the factory
        JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
        factory.setServiceClass(serviceClass);
        factory.setAddress(m_endpoint.getUrl().toExternalForm());

        WSAddressingFeature feature = new WSAddressingFeature();
        feature.setResponses(AddressingResponses.ANONYMOUS);
        factory.getFeatures().add(feature);

        // Force the client to use SOAP v1.2, as per:
        // R13.1-1: A service shall at least receive and send SOAP 1.2 SOAP Envelopes.
        factory.setBindingId(SoapBindingConstants.SOAP12_BINDING_ID);

        // Create the proxy service
        ProxyServiceType proxyService = factory.create(serviceClass);

        // Retrieve the underlying client, so we can fine tune it
        Client cxfClient = ClientProxy.getClient(proxyService);
        Map<String, Object> requestContext = cxfClient.getRequestContext();

        // Add static name-space mappings, this helps when manually inspecting the XML
        Map<String, String> nsMap = new HashMap<>();
        nsMap.put("wsa", WSManConstants.XML_NS_WS_2004_08_ADDRESSING);
        nsMap.put("wsen", WSManConstants.XML_NS_WS_2004_09_ENUMERATION);
        nsMap.put("wsman", WSManConstants.XML_NS_DMTF_WSMAN_V1);
        nsMap.put("wsmid", WSManConstants.XML_NS_DMTF_WSMAN_IDENTITY_V1);
        cxfClient.getRequestContext().put("soap.env.ns.map", nsMap);

        // Setup timeouts
        HTTPConduit http = (HTTPConduit)cxfClient.getConduit();
        HTTPClientPolicy httpClientPolicy = new HTTPClientPolicy();
        if (m_endpoint.getConnectionTimeout() != null) {
            httpClientPolicy.setConnectionTimeout(m_endpoint.getConnectionTimeout());
        }
        if (m_endpoint.getReceiveTimeout() != null) {
            httpClientPolicy.setReceiveTimeout(m_endpoint.getReceiveTimeout());
        }
        // Turn off chunking so that NTLM can occur
        httpClientPolicy.setAllowChunking(false);
        http.setClient(httpClientPolicy);

        if (!m_endpoint.isStrictSSL()) {
            LOG.debug("Disabling strict SSL checking.");
            // Accept all certificates
            TrustManager[] simpleTrustManager = new TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                }
                public void checkServerTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                }
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            } };
            TLSClientParameters tlsParams = new TLSClientParameters();
            tlsParams.setTrustManagers(simpleTrustManager);
            tlsParams.setDisableCNCheck(true);
            http.setTlsClientParameters(tlsParams);
        }

        // Setup authentication
        if (m_endpoint.isBasicAuth()) {
            LOG.debug("Enabling basic authentication.");
            http.setAuthSupplier(new DefaultBasicAuthSupplier());
            http.getAuthorization().setUserName(m_endpoint.getUsername());
            http.getAuthorization().setPassword(m_endpoint.getPassword());

            requestContext.put(BindingProvider.USERNAME_PROPERTY, m_endpoint.getUsername());
            requestContext.put(BindingProvider.PASSWORD_PROPERTY, m_endpoint.getPassword());
        } else if (m_endpoint.isGSSAuth()) {
            LOG.debug("Enabling GSS authentication.");
            http.getAuthorization().setAuthorizationType(HttpAuthHeader.AUTH_TYPE_NEGOTIATE);
        }

        // Set the Reply-To header to the anonymous address
        requestContext.put(JAXWSAConstants.CLIENT_ADDRESSING_PROPERTIES, createAddressingPropertiesMap());

        if (m_endpoint.getServerVersion() == WSManVersion.WSMAN_1_0) {
            // WS-Man 1.0 does not support the W3C WS-Addressing, so we need to change the namespace
            // "http://www.w3.org/2005/08/addressing" becomes "http://schemas.xmlsoap.org/ws/2004/08/addressing"
            outTransformMap.put("{" + JAXWSAConstants.NS_WSA + "}*",
                                "{" + WSManConstants.XML_NS_WS_2004_08_ADDRESSING + "}*");
        }

        // Optionally apply any in and/or out transformers
        if (!outTransformMap.isEmpty()) {
            final TransformOutInterceptor transformOutInterceptor = new TransformOutInterceptor();
            transformOutInterceptor.setOutTransformElements(outTransformMap);
            cxfClient.getOutInterceptors().add(transformOutInterceptor);
        }

        if (!inTransformMap.isEmpty()) {
            final TransformInInterceptor transformInInterceptor = new TransformInInterceptor();
            transformInInterceptor.setInTransformElements(inTransformMap);
            cxfClient.getInInterceptors().add(transformInInterceptor);
        }

        // Remove the action attribute from the Content-Type header.
        // By default, CXF will add the action to the Content-Type header, generating something like:
        // Content-Type: application/soap+xml; action="http://schemas.xmlsoap.org/ws/2004/09/enumeration/Enumerate"
        // Windows Server 2008 barfs on the action=".*" attribute and none of the other servers
        // seem to care whether it's there or not, so we remove it.
        Map<String, List<String>> headers = Maps.newHashMap();
        headers.put(CONTENT_TYPE_HEADER, Collections.singletonList(MEDIA_TYPE_SOAP_UTF8));
        requestContext.put(Message.PROTOCOL_HEADERS, headers);

        // Log incoming and outgoing requests
        LoggingInInterceptor loggingInInterceptor = new LoggingInInterceptor();
        loggingInInterceptor.setPrettyLogging(true);
        cxfClient.getInInterceptors().add(loggingInInterceptor);
 
        LoggingOutInterceptor loggingOutInterceptor = new LoggingOutInterceptor();
        loggingOutInterceptor.setPrettyLogging(true);
        cxfClient.getOutInterceptors().add(loggingOutInterceptor);

        return proxyService;
    }
 
    private static AddressingProperties createAddressingPropertiesMap() {
        AddressingProperties maps = new AddressingProperties();
        AttributedURIType address = WSA_OBJECT_FACTORY.createAttributedURIType();
        EndpointReferenceType ref = WSA_OBJECT_FACTORY.createEndpointReferenceType();
        address.setValue(VersionTransformer.Names200408.WSA_ANONYMOUS_ADDRESS);
        ref.setAddress(address);
        maps.setReplyTo(ref);
        maps.setFaultTo(ref);
        return maps;
    }

    /**
     * Wraps exceptions generated the CXF client proxy into
     * our WS-Man specific types.
     *
     * @param e thrown by the CXF client proxy
     * @return (possibly) wrapped exception
     */
    private static RuntimeException wrapException(RuntimeException e) {
        final Throwable cause = e.getCause();
        if (cause == null) {
            // Unknown exception
            return e;
        }

        if (cause instanceof org.apache.cxf.binding.soap.SoapFault) {
            final org.apache.cxf.binding.soap.SoapFault soapFault = (org.apache.cxf.binding.soap.SoapFault)cause;
            final QName subCode = soapFault.getSubCode();
            if (subCode != null && (WSManConstants.XML_NS_WS_2004_08_ADDRESSING.equals(soapFault.getSubCode().getNamespaceURI()) &&
                    "DestinationUnreachable".equals(soapFault.getSubCode().getLocalPart()))) {
                return new InvalidResourceURI(e);
            }
            throw new SOAPFault(e);
        } else if (cause instanceof org.apache.cxf.transport.http.HTTPException) {
            if (((org.apache.cxf.transport.http.HTTPException)cause).getResponseCode() == 401) {
                return new UnauthorizedException(e);
            }
            throw new HTTPException(e);
        }
        return new WSManException(e);
    }
}

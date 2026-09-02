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
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;

/**
 * Tests the Source-to-DOM conversion of shell responses against JAXP implementations that
 * do not understand the JAXP 1.5 access-restriction attributes. Xalan 2.7.x, which OpenNMS
 * ships on its classpath and which then wins the {@code TransformerFactory} lookup, rejects
 * {@code ACCESS_EXTERNAL_DTD} and {@code ACCESS_EXTERNAL_STYLESHEET} with an
 * {@code IllegalArgumentException}; that used to make every WinRS operation fail inside
 * OpenNMS while working from the standalone CLI.
 */
public class CxfShellOperationsTest {
    private static final String TRANSFORMER_FACTORY_PROPERTY = "javax.xml.transform.TransformerFactory";
    private static final String DOCUMENT_BUILDER_FACTORY_PROPERTY = "javax.xml.parsers.DocumentBuilderFactory";

    private static final String CREATE_RESPONSE =
        "<rsp:Shell xmlns:rsp=\"http://schemas.microsoft.com/wbem/wsman/1/windows/shell\">"
        + "<rsp:ShellId>C9D3E0B1-1C4B-4F0D-9A29-1F2E3D4C5B6A</rsp:ShellId>"
        + "<rsp:ResourceUri>http://schemas.microsoft.com/wbem/wsman/1/windows/shell/cmd</rsp:ResourceUri>"
        + "</rsp:Shell>";

    /** Attribute names the factory under test saw being set, shared with the fakes. */
    static final List<String> ATTRIBUTES_SET = new ArrayList<>();
    static final List<String> FEATURES_SET = new ArrayList<>();

    private String previousTransformerFactory;
    private String previousDocumentBuilderFactory;

    @Before
    public void rememberFactories() {
        previousTransformerFactory = System.getProperty(TRANSFORMER_FACTORY_PROPERTY);
        previousDocumentBuilderFactory = System.getProperty(DOCUMENT_BUILDER_FACTORY_PROPERTY);
        ATTRIBUTES_SET.clear();
        FEATURES_SET.clear();
    }

    @After
    public void restoreFactories() {
        restore(TRANSFORMER_FACTORY_PROPERTY, previousTransformerFactory);
        restore(DOCUMENT_BUILDER_FACTORY_PROPERTY, previousDocumentBuilderFactory);
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    @Test
    public void convertsStreamSourceWithTheDefaultFactories() {
        Element el = CxfShellOperations.responseToElement(new StreamSource(new StringReader(CREATE_RESPONSE)));
        assertShellElement(el);
    }

    @Test
    public void convertsStreamSourceWhenTransformerFactoryRejectsJaxp15Attributes() {
        System.setProperty(TRANSFORMER_FACTORY_PROPERTY, XalanLikeTransformerFactory.class.getName());

        Element el = CxfShellOperations.responseToElement(new StreamSource(new StringReader(CREATE_RESPONSE)));

        assertShellElement(el);
        // Both attributes were attempted; the implementation refused them and conversion went on
        assertTrue(ATTRIBUTES_SET.contains(XMLConstants.ACCESS_EXTERNAL_DTD));
        assertTrue(ATTRIBUTES_SET.contains(XMLConstants.ACCESS_EXTERNAL_STYLESHEET));
    }

    @Test
    public void appliesJaxp15AttributesWhenTheFactorySupportsThem() {
        System.setProperty(TRANSFORMER_FACTORY_PROPERTY, RecordingTransformerFactory.class.getName());

        Element el = CxfShellOperations.responseToElement(new StreamSource(new StringReader(CREATE_RESPONSE)));

        assertShellElement(el);
        assertTrue(ATTRIBUTES_SET.contains(XMLConstants.ACCESS_EXTERNAL_DTD));
        assertTrue(ATTRIBUTES_SET.contains(XMLConstants.ACCESS_EXTERNAL_STYLESHEET));
        assertTrue(FEATURES_SET.contains(XMLConstants.FEATURE_SECURE_PROCESSING));
    }

    @Test
    public void convertsStreamSourceWhenParserRejectsOptionalFeature() {
        System.setProperty(DOCUMENT_BUILDER_FACTORY_PROPERTY, DoctypeFeatureRejectingDocumentBuilderFactory.class.getName());

        Element el = CxfShellOperations.responseToElement(new StreamSource(new StringReader(CREATE_RESPONSE)));

        assertShellElement(el);
    }

    @Test(expected = IllegalStateException.class)
    public void wrapsRealConversionFailures() {
        CxfShellOperations.responseToElement(new StreamSource(new StringReader("<rsp:Shell>not well formed")));
    }

    private static void assertShellElement(Element el) {
        assertNotNull(el);
        assertEquals("Shell", el.getLocalName());
        assertEquals("http://schemas.microsoft.com/wbem/wsman/1/windows/shell", el.getNamespaceURI());
        assertEquals("C9D3E0B1-1C4B-4F0D-9A29-1F2E3D4C5B6A",
            el.getElementsByTagNameNS(el.getNamespaceURI(), "ShellId").item(0).getTextContent());
    }

    /**
     * Obtains the platform's own factory while a fake is registered through the system
     * property, so the fakes can delegate the real work to it.
     */
    private static TransformerFactory platformTransformerFactory() {
        String fake = System.getProperty(TRANSFORMER_FACTORY_PROPERTY);
        System.clearProperty(TRANSFORMER_FACTORY_PROPERTY);
        try {
            return TransformerFactory.newInstance();
        } finally {
            if (fake != null) {
                System.setProperty(TRANSFORMER_FACTORY_PROPERTY, fake);
            }
        }
    }

    private static DocumentBuilderFactory platformDocumentBuilderFactory() {
        String fake = System.getProperty(DOCUMENT_BUILDER_FACTORY_PROPERTY);
        System.clearProperty(DOCUMENT_BUILDER_FACTORY_PROPERTY);
        try {
            return DocumentBuilderFactory.newInstance();
        } finally {
            if (fake != null) {
                System.setProperty(DOCUMENT_BUILDER_FACTORY_PROPERTY, fake);
            }
        }
    }

    /** Delegates everything to the platform factory but records attribute and feature calls. */
    public static class RecordingTransformerFactory extends TransformerFactory {
        protected final TransformerFactory delegate = platformTransformerFactory();

        @Override
        public Transformer newTransformer(Source source) throws TransformerConfigurationException {
            return delegate.newTransformer(source);
        }

        @Override
        public Transformer newTransformer() throws TransformerConfigurationException {
            return delegate.newTransformer();
        }

        @Override
        public Templates newTemplates(Source source) throws TransformerConfigurationException {
            return delegate.newTemplates(source);
        }

        @Override
        public Source getAssociatedStylesheet(Source source, String media, String title, String charset)
                throws TransformerConfigurationException {
            return delegate.getAssociatedStylesheet(source, media, title, charset);
        }

        @Override
        public void setURIResolver(URIResolver resolver) {
            delegate.setURIResolver(resolver);
        }

        @Override
        public URIResolver getURIResolver() {
            return delegate.getURIResolver();
        }

        @Override
        public void setFeature(String name, boolean value) throws TransformerConfigurationException {
            FEATURES_SET.add(name);
            delegate.setFeature(name, value);
        }

        @Override
        public boolean getFeature(String name) {
            return delegate.getFeature(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            ATTRIBUTES_SET.add(name);
            delegate.setAttribute(name, value);
        }

        @Override
        public Object getAttribute(String name) {
            return delegate.getAttribute(name);
        }

        @Override
        public void setErrorListener(ErrorListener listener) {
            delegate.setErrorListener(listener);
        }

        @Override
        public ErrorListener getErrorListener() {
            return delegate.getErrorListener();
        }
    }

    /**
     * Behaves like Xalan 2.7.x: secure processing is supported, but the JAXP 1.5
     * access-restriction attributes are rejected with the same exception and message.
     */
    public static class XalanLikeTransformerFactory extends RecordingTransformerFactory {
        @Override
        public void setAttribute(String name, Object value) {
            ATTRIBUTES_SET.add(name);
            if (XMLConstants.ACCESS_EXTERNAL_DTD.equals(name) || XMLConstants.ACCESS_EXTERNAL_STYLESHEET.equals(name)) {
                throw new IllegalArgumentException("Not supported: " + name);
            }
            delegate.setAttribute(name, value);
        }
    }

    /** A parser factory that does not know the Xerces disallow-doctype-decl feature. */
    public static class DoctypeFeatureRejectingDocumentBuilderFactory extends DocumentBuilderFactory {
        private final DocumentBuilderFactory delegate = platformDocumentBuilderFactory();

        @Override
        public DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
            return delegate.newDocumentBuilder();
        }

        @Override
        public void setAttribute(String name, Object value) {
            delegate.setAttribute(name, value);
        }

        @Override
        public Object getAttribute(String name) {
            return delegate.getAttribute(name);
        }

        @Override
        public void setFeature(String name, boolean value) throws ParserConfigurationException {
            if ("http://apache.org/xml/features/disallow-doctype-decl".equals(name)) {
                throw new ParserConfigurationException("Feature not recognized: " + name);
            }
            delegate.setFeature(name, value);
        }

        @Override
        public boolean getFeature(String name) throws ParserConfigurationException {
            return delegate.getFeature(name);
        }

        @Override
        public void setNamespaceAware(boolean awareness) {
            delegate.setNamespaceAware(awareness);
        }

        @Override
        public void setExpandEntityReferences(boolean expand) {
            delegate.setExpandEntityReferences(expand);
        }
    }
}

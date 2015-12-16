package org.opennms.core.wsman.cxf;

import java.net.URI;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMResult;

import org.opennms.core.wsman.WSManConstants;
import org.opennms.core.wsman.WSManException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xmlsoap.schemas.ws._2004._09.enumeration.EnumerateResponse;
import org.xmlsoap.schemas.ws._2004._09.enumeration.EnumerationContextType;
import org.xmlsoap.schemas.ws._2004._09.enumeration.PullResponse;
import org.xmlsoap.schemas.ws._2004._09.transfer.TransferElement;

import schemas.dmtf.org.wbem.wsman.v1.AnyListType;

/**
 * Utility functions for manipulating WS-Man specific types.
 *
 * These functions should be very strict, and a {@link WSManException}
 * should be thrown if they encounter anything unexpected.
 *
 * @author jwhite
 */
public class TypeUtils {

    private final static QName WSEN_Items_QNAME = new QName(WSManConstants.XML_NS_WS_2004_09_ENUMERATION, "Items");
    private final static QName WSEN_EndOfSequence_QNAME = new QName(WSManConstants.XML_NS_WS_2004_09_ENUMERATION, "EndOfSequence");
    private final static QName WSMAN_Items_QNAME = new QName(WSManConstants.XML_NS_DMTF_WSMAN_V1, "Items");
    private final static QName WSMAN_EndOfSequence_QNAME = new QName(WSManConstants.XML_NS_DMTF_WSMAN_V1, "EndOfSequence");

    protected static String getContextIdFrom(EnumerateResponse response) {
        // A valid response must always include the EnumerationContext element
        return getContextIdFrom(response.getEnumerationContext());
    }

    protected static String getContextIdFrom(PullResponse response) {
        if (response.getEnumerationContext() == null) {
            // The PullResponse will not contain an EnumerationContext if EndOfSequence is set
            return null;
        }
        return getContextIdFrom(response.getEnumerationContext());
    }

    protected static String getContextIdFrom(EnumerationContextType context) {
        // The content of the EnumerationContext should contain a single string, the context id
        if (context.getContent() == null) {
            throw new WSManException(String.format("EnumerationContext %s has no content.", context));
        }

        if (context.getContent().size() == 0) {
            // The EnumerationContext can be empty if we issue an optimized enumeration
            // and all of the records are immediately returned
            return null;
        } else if (context.getContent().size() == 1) {
            Object content = context.getContent().get(0);
            if (content instanceof String) {
                return (String)content;
            } else {
                throw new WSManException(String.format("Unsupported EnumerationContext content: %s", content));
            }
        } else {
            throw new WSManException(String.format("EnumerationContext contains too many elements, expected: 1 actual: %d",
                    context.getContent().size()));
        }
    }

    /**
     * Retrieves the list of items from the given response, adding them to the given
     * list and returns true if the response contains an 'end-of-sequence' marker.
     */
    protected static boolean getItemsFrom(EnumerateResponse response, List<Node> items) {
        boolean endOfSequence = false;
        for (Object object : response.getAny()) {
            if (object instanceof JAXBElement) {
                JAXBElement<?> el = (JAXBElement<?>)object;
                if (WSEN_Items_QNAME.equals(el.getName()) || WSMAN_Items_QNAME.equals(el.getName())) {
                    if (el.isNil()) {
                        // No items
                    } else if (el.getValue() instanceof AnyListType) {
                        // 0+ items
                        AnyListType itemList = (AnyListType)el.getValue();
                        for (Object item : itemList.getAny()) {
                            if (item instanceof Node) {
                                Node node = (Node)item;
                                items.add(node);
                            } else {
                                throw new WSManException(String.format("Unsupported element in EnumerateResponse: %s", object));
                            }
                        }
                    } else {
                        throw new WSManException(String.format("Unsupported value in EnumerateResponse Items: %s of type: %s",
                                el.getValue(), el.getValue().getClass()));
                    }
                } else if (WSEN_EndOfSequence_QNAME.equals(el.getName()) || WSMAN_EndOfSequence_QNAME.equals(el.getName())) {
                    endOfSequence = true;
                } else {
                    throw new WSManException(String.format("Unsupported element in EnumerateResponse: %s with name: %s", el, el.getName()));
                }
            } else if (object instanceof Node) {
                Node node = (Node)object;
                if ((WSEN_EndOfSequence_QNAME.getNamespaceURI().equals(node.getNamespaceURI()) && WSEN_EndOfSequence_QNAME.getLocalPart().equals(node.getLocalName())) ||
                        (WSMAN_EndOfSequence_QNAME.getNamespaceURI().equals(node.getNamespaceURI()) && WSMAN_EndOfSequence_QNAME.getLocalPart().equals(node.getLocalName()))) {
                    endOfSequence = true;
                } else {
                    throw new WSManException(String.format("Unsupported node in EnumerateResponse: %s with namespace: %s", node, node.getNamespaceURI()));
                }
            } else {
                throw new WSManException(String.format("Unsupported element in EnumerateResponse: %s, with type: %s",
                        object, object != null ? object.getClass() : null));
            }
        }
        return endOfSequence;
    }

    protected static boolean getItemsFrom(PullResponse response, List<Node> items) {
        for (Object item : response.getItems().getAny()) {
            if (item instanceof Node) {
                items.add((Node)item);
            } else {
                throw new WSManException(String.format("The pull response contains an unsupported item %s of type %s",
                        item, item != null ? item.getClass() : null));
            }
        }
        return response.getEndOfSequence() != null;
    }

    protected static Node transferElementToNode(TransferElement el, String namespaceURI, String qualifiedName) {
        try {
            // Marshall the transfer element back to a DOM Node
            DOMResult domResult = new DOMResult();
            JAXBContext context = JAXBContext.newInstance(el.getClass());
            context.createMarshaller().marshal(el, domResult);

            // Convert the node back to it's original type
            Document doc = (Document)domResult.getNode();
            Node node = doc.getFirstChild();
            doc.renameNode(node, namespaceURI, qualifiedName);
            return doc.getFirstChild();
        } catch (JAXBException e) {
            throw new WSManException("XML serialization failed.", e);
        }
    }

    protected static String getElementTypeFromResourceUri(String resourceUri) {
        String elementType = null;
        try {
            URI uri = new URI(resourceUri);
            String path = uri.getPath();
            elementType = path.substring(path.lastIndexOf('/') + 1);
        } catch (Throwable t) {
            throw new WSManException("Failed to determine the element type from resource uri: " + resourceUri, t);
        }
        return elementType;
    }
}

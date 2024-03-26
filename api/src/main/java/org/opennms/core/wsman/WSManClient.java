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

import org.opennms.core.wsman.exceptions.WSManException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.List;
import java.util.Map;

/**
 * A WS-Man client implementation that supports the following operations:
 *   * Enumerate and pull (DSP8037)
 *   * Get (DSP8035)
 *   * Identify (DSP0226)
 *
 * @author jwhite
 */
public interface WSManClient {

    /**
     * Discovers the capabilities and version information of the remote service.
     *
     * @return identify response
     * @throws WSManException on error
     */
    public Identity identify();

    /**
     * Retrieves a single element using the provided selectors.
     *
     * @param resourceUri uri
     * @param selectors map of selectors
     * @return the requested node, otherwise an exception is thrown
     * @throws WSManException on error
     */
    public Node get(String resourceUri, Map<String, String> selectors);

    /**
     * Starts a new enumeration context.
     *
     * @param resourceUri uri
     * @return context id
     * @throws WSManException on error
     */
    public String enumerate(String resourceUri);

    /**
     * Starts a new enumeration context using a filter.
     *
     * @param resourceUri uri
     * @param dialect used by the filter
     * @param filter query
     * @return context id
     * @throws WSManException on error
     */
    public String enumerateWithFilter(String resourceUri, String dialect, String filter);

    /**
     * Pulls elements from an existing enumeration context.
     *
     * @param contextId the context id
     * @param resourceUri uri
     * @param nodes existing list in which the pulled elements will be added
     * @param recursive when true, the implementation will continue to pull
     * until the 'EndOfSequence' is reached
     * @return the next context id, when pulling recursively this will be null
     * @throws WSManException on error
     */
    public String pull(String contextId, String resourceUri, List<Node> nodes, boolean recursive);

    /**
     * Optimized version of the enumerate and pull operations.
     *
     * The implementation should attempt to consolidate the calls using optimized enumeration.
     *
     * @param resourceUri uri
     * @param nodes existing list in which the pulled elements will be added
     * @param recursive when true, the implementation will continue to pull
     * until the 'EndOfSequence' is reached
     * @return the next context id, when pulling recursively this will be null
     * @throws WSManException on error
     */
    public String enumerateAndPull(String resourceUri, List<Node> nodes, boolean recursive);

    /**
     * Optimized version of the enumerate and pull operations.
     *
     * The implementation should attempt to consolidate the calls using optimized enumeration.
     *
     * @param resourceUri uri
     * @param dialect used by the filter
     * @param filter query
     * @param recursive when true, the implementation will continue to pull
     * until the 'EndOfSequence' is reached
     * @return the next context id, when pulling recursively this will be null
     * @throws WSManException on error
     */
    public String enumerateAndPullUsingFilter(String resourceUri, String dialect, String filter, List<Node> nodes, boolean recursive);

    /**
     *
     *
     * @param resourceUri uri
     * @param body body with a root XML element
     * @param selectors map of selectors
     * @throws WSManException on error
     */
    public void put(String resourceUri, Element body, Map<String, String> selectors);
}

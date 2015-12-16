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

import java.util.List;
import java.util.Map;

import org.w3c.dom.Node;

/**
 * A WS-Man client implementation that supports the following operations:
 *   * Enumerate and pull (DSP8037)
 *   * Get (DSP8035)
 *
 * @author jwhite
 */
public interface WSManClient {

    /**
     * Retrieves a single element using the provided selectors.
     *
     * @param resourceUri
     * @param selectors
     * @return the requested node, otherwise an exception is thrown
     * @throw WSManException
     */
    Node get(String resourceUri, Map<String, String> selectors);

    /**
     * Starts a new enumeration context.
     *
     * @param resourceUri
     * @return context id
     */
    public String enumerate(String resourceUri);

    /**
     * Starts a new enumeration context using a filter.
     *
     * @param resourceUri
     * @param dialect
     * @param filter
     * @return context id
     */
    public String enumerateWithFilter(String resourceUri, String dialect, String filter);

    /**
     * Pulls elements from an existing enumeration context.
     *
     * @param contextId the context id
     * @param resourceUri
     * @param nodes existing list in which the pulled elements will be added
     * @param recursive when true, the implementation will continue to pull
     * until the 'EndOfSequence' is reached
     * @return the next context id, when pulling recursively this will be null
     */
    public String pull(String contextId, String resourceUri, List<Node> nodes, boolean recursive);

    /**
     * Optimized version of the enumerate and pull operations.
     *
     * The implementation should attempt to consolidate the calls using optimized enumeration.
     *
     * @param resourceUri
     * @param nodes existing list in which the pulled elements will be added
     * @param recursive when true, the implementation will continue to pull
     * until the 'EndOfSequence' is reached
     * @return the next context id, when pulling recursively this will be null
     */
    public String enumerateAndPull(String resourceUri, List<Node> nodes, boolean recursive);

    /**
     * Optimized version of the enumerate and pull operations.
     *
     * The implementation should attempt to consolidate the calls using optimized enumeration.
     *
     * @param resourceUri
     * @param dialect
     * @param filter
     * @param recursive when true, the implementation will continue to pull
     * until the 'EndOfSequence' is reached
     * @return the next context id, when pulling recursively this will be null
     */
    public String enumerateAndPullUsingFilter(String resourceUri, String dialect, String filter, List<Node> nodes, boolean recursive);
}

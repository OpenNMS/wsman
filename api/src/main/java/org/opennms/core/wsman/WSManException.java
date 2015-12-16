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
 * Generic WS-Man related exception.
 *
 * @author jwhite
 */
public class WSManException extends RuntimeException {
    private static final long serialVersionUID = -2894934806760355903L;

    public WSManException(String message) {
        super(message);
    }

    public WSManException(String message, Throwable cause) {
        super(message, cause);
    }
}

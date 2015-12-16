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
package org.opennms.core.wsman.openwsman;

import org.opennms.core.wsman.WSManClientFactory;
import org.opennms.core.wsman.WSManEndpoint;

/**
 * Used to instantiate a new  {@link OpenWSManClient} from a
 * given {@link WSManEndpoint}.
 *
 * @author jwhite
 */
public class OpenWSManClientFactory implements WSManClientFactory {

    @Override
    public OpenWSManClient getClient(WSManEndpoint endpoint) {
        return new OpenWSManClient(endpoint);
    }
}

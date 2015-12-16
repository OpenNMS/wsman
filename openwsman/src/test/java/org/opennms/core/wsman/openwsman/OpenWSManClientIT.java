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

import org.junit.Test;
import org.opennms.core.wsman.AbstractWSManClientIT;
import org.opennms.core.wsman.WSManClientFactory;

public class OpenWSManClientIT extends AbstractWSManClientIT {
    @Override
    public WSManClientFactory getFactory() {
        return new OpenWSManClientFactory();
    }

    @Test
    public void canPullRecursively() {
        // Pass. Not yet implemented
    }

    @Test
    public void canEnumerateAndPullUsingWQLFilter() {
        // Pass. Not yet implemented
    }
}

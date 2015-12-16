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
package org.opennms.core.wsman.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.opennms.core.wsman.WSManEndpoint;
import org.opennms.core.wsman.WSManVersion;

public class TestUtils {
    public static WSManEndpoint getEndpointFromLocalConfiguration() throws FileNotFoundException, IOException {
        Properties prop = new Properties();
        try (InputStream input = new FileInputStream(new File(System.getProperty("user.home"), "wsman.properties"))) {
            prop.load(input);
            String hostname = prop.getProperty("hostname" , "127.0.0.1");
            int port = Integer.valueOf(prop.getProperty("port", "443"));
            String path = prop.getProperty("url" , "/wsman");
            String protocol = prop.getProperty("protocol" , "https");

            String url = String.format("%s://%s:%d%s", protocol, hostname, port, path);

            return new WSManEndpoint.Builder(url)
                        .withBasicAuth(prop.getProperty("username" , "admin"), prop.getProperty("password" , "admin"))
                        .withStrictSSL(false)
                        .withServerVersion(WSManVersion.WSMAN_1_0)
                        .withMaxElements(25)
                        .withMaxEnvelopeSize(1024*1024)
                        .build();
        }
    }
}

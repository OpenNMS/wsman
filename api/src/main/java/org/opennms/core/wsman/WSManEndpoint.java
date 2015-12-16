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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

/**
 * Used to define all the details on how to communicate
 * with a particular WS-Man endpoint.
 *
 * These objects are immutable and can be constructed
 * using the fluent API.
 *
 * @author jwhite
 */
public class WSManEndpoint {

    private final URL url;
    private final String username;
    private final String password;
    private final boolean strictSSL;
    private final WSManVersion serverVersion;
    private final Integer maxElements;
    private final Integer maxEnvelopeSize;

    private WSManEndpoint(Builder builder) {
        url = builder.url;
        username = builder.username;
        password = builder.password;
        strictSSL = builder.strictSSL;
        serverVersion = builder.serverVersion;
        maxElements = builder.maxElements;
        maxEnvelopeSize = builder.maxEnvelopeSize;
    }

    public static class Builder {
       private final URL url;
       private boolean strictSSL = true;
       private String username;
       private String password;
       private WSManVersion serverVersion = WSManVersion.WSMAN_1_2;
       private Integer maxElements;
       private Integer maxEnvelopeSize;

       public Builder(String url) throws MalformedURLException {
           this(new URL(Objects.requireNonNull(url, "url cannot be null")));
       }

       public Builder(URL url) {
           this.url = Objects.requireNonNull(url, "url cannot be null");
       }

       public Builder withBasicAuth(String username, String password) {
           this.username = Objects.requireNonNull(username, "username cannot be null");
           this.password = Objects.requireNonNull(password, "password cannot be null");
           return this;
       }

       public Builder withStrictSSL(boolean strictSSL) {
           this.strictSSL = strictSSL;
           return this;
       }

       public Builder withServerVersion(WSManVersion version) {
           this.serverVersion = Objects.requireNonNull(version, "version cannot be null");
           return this;
       }

       public Builder withMaxElements(Integer maxElements) {
           if (maxElements == null || maxElements < 1) {
               throw new IllegalArgumentException("maxElements must be strictly positive");
           }
           this.maxElements = maxElements;
           return this;
       }

       public Builder withMaxEnvelopeSize(Integer maxEnvelopeSize) {
           if (maxEnvelopeSize == null || maxEnvelopeSize < 1) {
               throw new IllegalArgumentException("maxEnvelopeSize must be strictly positive");
           }
           this.maxEnvelopeSize = maxEnvelopeSize;
           return this;
       }

       public WSManEndpoint build() {
           return new WSManEndpoint(this);
       }
    }

    public URL getUrl() {
        return url;
    }

    public boolean isBasicAuth() {
        return username != null;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean isStrictSSL() {
        return strictSSL;
    }

    public WSManVersion getServerVersion() {
        return serverVersion;
    }

    public Integer getMaxElements() {
        return maxElements;
    }

    public Integer getMaxEnvelopeSize() {
        return maxEnvelopeSize;
    }

    public String toString() {
        return String.format("WSManEndpoint[url='%s', isBasicAuth='%s', isStrictSSL='%s', "
                + "serverVersion='%s',  maxElements='%s', maxEnvelopeSize='%s']",
                url, isBasicAuth(), isStrictSSL(), serverVersion,
                maxElements, maxEnvelopeSize);
    }
}

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
    private final boolean gssAuth;
    private final boolean basicAuth;
    private final boolean digestAuth;
    private final boolean strictSSL;
    private final WSManVersion serverVersion;
    private final Integer maxElements;
    private final Integer maxEnvelopeSize;
    private final Integer connectionTimeout;
    private final Integer receiveTimeout;

    private WSManEndpoint(Builder builder) {
        url = builder.url;
        username = builder.username;
        password = builder.password;
        gssAuth = builder.gssAuth;
        basicAuth = builder.basicAuth;
        digestAuth = builder.digestAuth;
        strictSSL = builder.strictSSL;
        serverVersion = builder.serverVersion;
        maxElements = builder.maxElements;
        maxEnvelopeSize = builder.maxEnvelopeSize;
        connectionTimeout = builder.connectionTimeout;
        receiveTimeout = builder.receiveTimeout;
    }

    public static class Builder {
       private final URL url;
       private boolean strictSSL = true;
       private String username;
       private String password;
       private boolean gssAuth = false;
       private boolean basicAuth = false;
       private boolean digestAuth = false;
       private WSManVersion serverVersion = WSManVersion.WSMAN_1_2;
       private Integer maxElements;
       private Integer maxEnvelopeSize;
       private Integer connectionTimeout;
       private Integer receiveTimeout;

       public Builder(String url) throws MalformedURLException {
           this(new URL(Objects.requireNonNull(url, "url cannot be null")));
       }

       public Builder(URL url) {
           this.url = Objects.requireNonNull(url, "url cannot be null");
       }

       public Builder withBasicAuth(String username, String password) {
           this.basicAuth = true;
           this.username = Objects.requireNonNull(username, "username cannot be null");
           this.password = Objects.requireNonNull(password, "password cannot be null");
           return this;
       }

        public Builder withDigestAuth(String username, String password) {
            this.digestAuth= true;
            this.username = Objects.requireNonNull(username, "username cannot be null");
            this.password = Objects.requireNonNull(password, "password cannot be null");
            return this;
        }

       public Builder withGSSAuth() {
           gssAuth = true;
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

       public Builder withConnectionTimeout(Integer connectionTimeout) {
           if (connectionTimeout == null || connectionTimeout < 0) {
               throw new IllegalArgumentException("connectionTimeout must be non-negative");
           }
           this.connectionTimeout = connectionTimeout;
           return this;
       }

       public Builder withReceiveTimeout(Integer receiveTimeout) {
           if (receiveTimeout == null || receiveTimeout < 0) {
               throw new IllegalArgumentException("receiveTimeout must be non-negative");
           }
           this.receiveTimeout = receiveTimeout;
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
        return !isGSSAuth() && basicAuth && username != null;
    }

    public boolean isDigestAuth() {
        return !isGSSAuth() && digestAuth && username != null;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean isGSSAuth() {
        return gssAuth;
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

    public Integer getConnectionTimeout() {
        return connectionTimeout;
    }

    public Integer getReceiveTimeout() {
        return receiveTimeout;
    }

    public String toString() {
        return String.format("WSManEndpoint[url='%s', isGSSAuth='%s', isBasicAuth='%s', isStrictSSL='%s', "
                + "serverVersion='%s',  maxElements='%s', maxEnvelopeSize='%s'"
                + "connectionTimeout='%s', receiveTimeout='%s']",
                url, isGSSAuth(), isBasicAuth(), isStrictSSL(), serverVersion,
                maxElements, maxEnvelopeSize, connectionTimeout, receiveTimeout);
    }
}

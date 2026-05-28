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
package org.opennms.core.wsman.shell;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Options passed to {@code rsp:Shell} on shell creation. Defaults are tuned for monitoring
 * workloads: skip user profile load, UTF-8 code page, short idle timeout.
 */
public final class ShellOptions {
    private final boolean noProfile;
    private final int codepage;
    private final Duration idleTimeout;
    private final String workingDirectory;
    private final Map<String, String> environment;

    private ShellOptions(Builder b) {
        this.noProfile = b.noProfile;
        this.codepage = b.codepage;
        this.idleTimeout = b.idleTimeout;
        this.workingDirectory = b.workingDirectory;
        this.environment = b.environment.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(b.environment));
    }

    public static ShellOptions defaults() {
        return new Builder().build();
    }

    public boolean isNoProfile() {
        return noProfile;
    }

    public int getCodepage() {
        return codepage;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public static final class Builder {
        private boolean noProfile = true;
        private int codepage = 65001; // UTF-8
        private Duration idleTimeout = Duration.ofMinutes(3);
        private String workingDirectory;
        private final Map<String, String> environment = new LinkedHashMap<>();

        public Builder withNoProfile(boolean noProfile) {
            this.noProfile = noProfile;
            return this;
        }

        public Builder withCodepage(int codepage) {
            this.codepage = codepage;
            return this;
        }

        public Builder withIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }

        public Builder withWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder withEnvironmentVariable(String name, String value) {
            this.environment.put(name, value);
            return this;
        }

        public ShellOptions build() {
            return new ShellOptions(this);
        }
    }
}

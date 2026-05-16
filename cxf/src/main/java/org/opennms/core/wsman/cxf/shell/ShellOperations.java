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
package org.opennms.core.wsman.cxf.shell;

import java.time.Duration;

import org.opennms.core.wsman.shell.ShellOptions;
import org.w3c.dom.Element;

/**
 * The five WinRS shell wire operations, decoupled from the HTTP transport so the
 * orchestrator {@link WinRSClient} can be unit-tested against fakes.
 *
 * <p>The CXF-backed implementation is {@link CxfShellOperations}; callers needing to
 * configure the underlying CXF {@code Client} (interceptors, TLS, auth) should construct
 * a {@link CxfShellOperations} directly so the implementation-specific {@code getClient()}
 * accessor is available.
 */
public interface ShellOperations {

    Element create(Element shellBody, ShellOptions options);

    Element command(String shellId, Element commandBody);

    /**
     * Sends a Receive request. The server parks this call for up to {@code operationTimeout}
     * waiting on stdout/stderr output before either returning what it has or replying with a
     * {@code wsman:TimedOut} fault that the caller is expected to ignore and retry.
     */
    Element receive(String shellId, Element receiveBody, Duration operationTimeout);

    Element signal(String shellId, Element signalBody);

    void delete(String shellId);
}
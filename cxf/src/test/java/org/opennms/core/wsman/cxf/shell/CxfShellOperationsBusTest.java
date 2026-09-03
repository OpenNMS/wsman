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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.buslifecycle.BusLifeCycleListener;
import org.apache.cxf.buslifecycle.BusLifeCycleManager;
import org.junit.Test;

/**
 * The WinRS shell Dispatch must live on its own private Bus. CXFWSManClient.runCommand()
 * shuts that Bus down after every command; if the Dispatch were bound to the JVM-wide
 * default bus (which is what a bare {@code Service.create()} produces), that shutdown
 * would tear down every other CXF endpoint in an embedding application, e.g. the
 * OpenNMS v2 REST API endpoints.
 */
public class CxfShellOperationsBusTest {

    @Test
    public void shellDispatchRunsOnItsOwnBus_andItsShutdownLeavesTheDefaultBusAlone() {
        Bus defaultBus = BusFactory.getDefaultBus(true);
        AtomicBoolean defaultBusShutdown = new AtomicBoolean(false);
        defaultBus.getExtension(BusLifeCycleManager.class).registerLifeCycleListener(
            new BusLifeCycleListener() {
                @Override public void initComplete() {}
                @Override public void preShutdown() { defaultBusShutdown.set(true); }
                @Override public void postShutdown() { defaultBusShutdown.set(true); }
            });
        Bus threadBusBefore = BusFactory.getThreadDefaultBus(false);

        CxfShellOperations ops = new CxfShellOperations("http://shell-bus-test.invalid:5985/wsman");
        Bus shellBus = ops.getClient().getBus();

        assertNotSame("the shell Dispatch must not ride the JVM default bus",
            defaultBus, shellBus);
        assertSame("the thread-default bus must be restored after construction",
            threadBusBefore, BusFactory.getThreadDefaultBus(false));

        // The exact teardown CXFWSManClient.runCommand() performs after each command:
        // it must only affect the shell's private bus.
        shellBus.shutdown(true);
        assertFalse("shutting down the shell's bus must not shut down the default bus",
            defaultBusShutdown.get());
    }
}

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
package org.opennms.core.wsman.cxf;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.MalformedURLException;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.transport.http.HTTPConduitFactory;
import org.junit.Test;
import org.opennms.core.wsman.WSManEndpoint;
import org.opennms.core.wsman.cxf.shell.CxfShellOperations;

/**
 * Regression test for the Kerberos conduit-factory scoping: the WinRS shell path builds
 * its Dispatch via {@code Service.create(...)}, which resolves to the JVM's thread-default
 * bus, shared with every other CXF client on the thread. The factory that produces
 * {@link KerberosHttpConduit}s must therefore be installed as an EndpointInfo property
 * (consulted first by {@code HTTPTransportFactory.findFactory()}), never as a bus
 * extension: a bus-level registration would permanently hand this WS-Man host's encrypted
 * conduit, and its session-bound socket, to unrelated CXF clients in the JVM.
 *
 * <p>Everything here is offline: conduit creation constructs objects but opens no
 * connection, and the Kerberos session performs no login or handshake until first use.
 */
public class KerberosConduitScopingTest {

    @Test
    public void shellConduitFactory_isEndpointScoped_notOnThreadDefaultBus() throws MalformedURLException {
        WSManEndpoint endpoint = new WSManEndpoint.Builder("http://kerberos-target.example.com:5985/wsman")
                .withKerberosEncryption()
                .build();
        CXFWSManClient client = new CXFWSManClient(endpoint);
        CxfShellOperations shellOps = new CxfShellOperations(endpoint.getUrl().toExternalForm());
        CxfShellOperations unrelated = new CxfShellOperations("http://unrelated.example.com:5985/other");
        try {
            // Premise of the finding: the shell Dispatch really does live on the JVM's
            // thread-default bus. If a CXF upgrade changes this, the scoping requirement
            // needs re-evaluation, so fail loudly here.
            Bus threadDefaultBus = BusFactory.getThreadDefaultBus();
            assertSame("CxfShellOperations is expected to build its Dispatch on the"
                    + " thread-default bus", threadDefaultBus, shellOps.getClient().getBus());

            client.configureShellConduit(shellOps.getClient());

            // The shell endpoint itself gets the encrypted conduit...
            assertTrue("shell endpoint should get a KerberosHttpConduit",
                    shellOps.getClient().getConduit() instanceof KerberosHttpConduit);

            // ...but the shared bus carries no trace of it...
            assertNull("thread-default bus must not carry the Kerberos conduit factory",
                    threadDefaultBus.getExtension(HTTPConduitFactory.class));

            // ...so an unrelated CXF client on the same bus still gets a stock conduit.
            assertFalse("unrelated client must not inherit the Kerberos conduit",
                    unrelated.getClient().getConduit() instanceof KerberosHttpConduit);
        } finally {
            try {
                unrelated.getClient().destroy();
            } catch (Exception ignored) {}
            try {
                shellOps.getClient().destroy();
            } catch (Exception ignored) {}
            client.close();
        }
    }
}

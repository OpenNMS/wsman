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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.MalformedURLException;

import org.apache.cxf.transport.http.HTTPConduitFactory;
import org.junit.Test;
import org.opennms.core.wsman.WSManEndpoint;
import org.opennms.core.wsman.cxf.shell.CxfShellOperations;

/**
 * Regression test for the Kerberos conduit-factory scoping. Two isolation mechanisms
 * hold at once:
 * <ul>
 *   <li>{@link CxfShellOperations} builds its Dispatch on a private Bus (never the
 *       JVM-wide thread-default bus), so the per-command bus shutdown in
 *       {@code CXFWSManClient.runCommand()} cannot tear down unrelated CXF endpoints
 *       in an embedding application.</li>
 *   <li>The factory that produces {@link KerberosHttpConduit}s is installed as an
 *       EndpointInfo property (consulted first by
 *       {@code HTTPTransportFactory.findFactory()}), never as a bus extension, so even
 *       clients that do share a bus can never inherit this WS-Man host's encrypted
 *       conduit and its session-bound socket.</li>
 * </ul>
 *
 * <p>Everything here is offline: conduit creation constructs objects but opens no
 * connection, and the Kerberos session performs no login or handshake until first use.
 */
public class KerberosConduitScopingTest {

    @Test
    public void shellConduitFactory_isEndpointScoped_andEachShellGetsItsOwnBus() throws MalformedURLException {
        WSManEndpoint endpoint = new WSManEndpoint.Builder("http://kerberos-target.example.com:5985/wsman")
                .withKerberosEncryption()
                .build();
        CXFWSManClient client = new CXFWSManClient(endpoint);
        CxfShellOperations shellOps = new CxfShellOperations(endpoint.getUrl().toExternalForm());
        CxfShellOperations unrelated = new CxfShellOperations("http://unrelated.example.com:5985/other");
        try {
            // Each CxfShellOperations builds its Dispatch on its own private bus, never a
            // bus shared with another instance. (Bus-vs-default-bus isolation, the actual
            // OpenNMS regression, is asserted directly in CxfShellOperationsBusTest; here
            // we only need the buses to be distinct so the conduit-scoping checks below
            // are meaningful. Deliberately not compared against getThreadDefaultBus(): a
            // freshly created bus may itself become the JVM's lazily-resolved default, so
            // that comparison is test-ordering dependent.)
            assertNotSame("each CxfShellOperations must get its own bus",
                    shellOps.getClient().getBus(), unrelated.getClient().getBus());

            client.configureShellConduit(shellOps.getClient());

            // The shell endpoint itself gets the encrypted conduit...
            assertTrue("shell endpoint should get a KerberosHttpConduit",
                    shellOps.getClient().getConduit() instanceof KerberosHttpConduit);

            // ...but no bus carries a trace of it (the factory is endpoint-scoped, so it
            // cannot leak to other clients that happen to share a bus)...
            assertNull("the shell's own bus must not carry the Kerberos conduit factory",
                    shellOps.getClient().getBus().getExtension(HTTPConduitFactory.class));
            assertNull("the unrelated client's bus must not carry the Kerberos conduit factory",
                    unrelated.getClient().getBus().getExtension(HTTPConduitFactory.class));

            // ...so an unrelated CXF client still gets a stock conduit.
            assertFalse("unrelated client must not inherit the Kerberos conduit",
                    unrelated.getClient().getConduit() instanceof KerberosHttpConduit);
        } finally {
            try {
                unrelated.getClient().getBus().shutdown(true);
                unrelated.getClient().destroy();
            } catch (Exception ignored) {}
            try {
                shellOps.getClient().getBus().shutdown(true);
                shellOps.getClient().destroy();
            } catch (Exception ignored) {}
            client.close();
        }
    }
}

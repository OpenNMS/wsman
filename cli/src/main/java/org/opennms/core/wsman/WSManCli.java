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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.spi.StandardLevel;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;
import org.kohsuke.args4j.spi.MapOptionHandler;
import org.opennms.core.wsman.cxf.CXFWSManClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.google.common.collect.Lists;

public class WSManCli {
    private static Logger LOG = LoggerFactory.getLogger(WSManCli.class);

    public static enum WSManOperation {
        GET,
        ENUM
    }

    @Option(name="-r", usage="remote url", metaVar="url", required=true)
    private String remoteUrl;

    @Option(name="-u", usage="username")
    private String username;

    @Option(name="-p", usage="password")
    private String password;

    @Option(name="-strictSSL", usage="ssl certificate verification")
    private boolean strictSSL = false;

    @Option(name="-o", usage="operation")
    WSManOperation operation = WSManOperation.ENUM;

    @Option(name="-resourceUri", usage="resource uri")
    private String resourceUri = WSManConstants.CIM_ALL_AVAILABLE_CLASSES;

    @Option(name="-w", usage="server version")
    private WSManVersion serverVersion = WSManVersion.WSMAN_1_2;

    @Option(name="-v", usage="logging level")
    private StandardLevel logLevel = StandardLevel.INFO;

    @Option(name="-vvv", usage="log request and responses")
    private boolean logRequests = false;

    @Option(name="-s", handler=MapOptionHandler.class)
    private Map<String,String> selectors;

    @Argument
    private List<String> arguments = new ArrayList<String>();

    private WSManClientFactory clientFactory = new CXFWSManClientFactory();
    
    public static void main(String[] args) {
        new WSManCli().doMain(args);
    }

    public void doMain(String[] args) {
        ParserProperties parserProperties = ParserProperties.defaults()
                .withUsageWidth(120);

        CmdLineParser parser = new CmdLineParser(this, parserProperties);

        try {
            parser.parseArgument(args);
        } catch( CmdLineException e ) {
            System.err.println("java -jar wsman4j.jar [options...] arguments...");
            parser.printUsage(System.err);
            System.err.println();
            e.printStackTrace();
            return;
        }

        setupLogging();

        URL url;
        try {
            url = new URL(remoteUrl);
        } catch (MalformedURLException e) {
            LOG.error("Invalid URL: {}", remoteUrl, e);
            return;
        }

        WSManEndpoint.Builder builder = new WSManEndpoint.Builder(url)
                .withStrictSSL(strictSSL)
                .withServerVersion(serverVersion)
                .withMaxElements(100);
        if (username != null && password != null) {
            builder.withBasicAuth(username, password);
        }
        WSManEndpoint endpoint = builder.build();
        LOG.info("Using endpoint: {}", endpoint);
        WSManClient client = clientFactory.getClient(endpoint);
        
        if (operation == WSManOperation.ENUM) {
            List<Node> nodes = Lists.newLinkedList();
            if (arguments.isEmpty()) {
                LOG.info("Enumerating and pulling on '{}'...", resourceUri);
                client.enumerateAndPull(resourceUri, nodes , true);
                LOG.info("Succesfully pulled {} nodes.", nodes.size());
            } else {
                for (String wql : arguments) {
                    LOG.info("Enumerating and pulling on '{}' with '{}'...", resourceUri, wql);
                    client.enumerateAndPullUsingFilter(resourceUri, WSManConstants.XML_NS_WQL_DIALECT, wql, nodes, true);
                    LOG.info("Succesfully pulled {} nodes.", nodes.size());
                }
            }

            // Dump the list of nodes to stdout
            for (Node node : nodes) {
                dumpNodeToStdout(node);
            }
        } else if (operation == WSManOperation.GET) {
            LOG.info("Issuing a GET on '{}' with selectors {}", resourceUri, selectors);
            Node node = client.get(resourceUri, selectors);
            LOG.info("GET successful.");

            // Dump the node to stdout
            dumpNodeToStdout(node);
        }
    }

    private void setupLogging() {
        Level level = Level.getLevel(logLevel.name());
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        // Setup the root logger to the requested log level
        LoggerConfig loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
        loggerConfig.setLevel(level);
        // Dump the requests/responses when requested
        if (logRequests) {
            loggerConfig = config.getLoggerConfig("org.apache.cxf.services");
            if (level.isLessSpecificThan(Level.INFO)) {
                loggerConfig.setLevel(level);
            } else {
                loggerConfig.setLevel(Level.INFO);
            }
        }
        ctx.updateLoggers();
    }

    private static void dumpNodeToStdout(Node node) {
        System.out.printf("%s (%s)\n", node.getLocalName(), node.getNamespaceURI());
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            
            if (child.getLocalName() == null) {
                continue;
            }

            System.out.printf("\t%s = %s\n", child.getLocalName(), child.getTextContent());
        }
    }
}


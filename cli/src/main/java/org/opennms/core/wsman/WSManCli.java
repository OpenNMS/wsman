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
package org.opennms.core.wsman;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
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
import org.opennms.core.wsman.shell.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class WSManCli {
    private static Logger LOG = LoggerFactory.getLogger(WSManCli.class);

    public enum WSManOperation {
        GET,
        ENUM,
        IDENTIFY,
        SHELL
    }

    @Option(name="-r", usage="remote url", metaVar="url", required=true)
    private String remoteUrl;

    @Option(name="-u", usage="username")
    private String username;

    @Option(name="-p", usage="password")
    private String password;

    @Option(name="-strictSSL", usage="ssl certificate verification")
    private boolean strictSSL = false;

    @Option(name="-gssAuth", usage="GSS authentication")
    private boolean gssAuth = false;

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

    @Option(name="-timeout", usage="command timeout in seconds (SHELL only)", metaVar="seconds")
    private int timeoutSeconds = 60;

    @Argument
    private List<String> arguments = new ArrayList<>();

    private WSManClientFactory clientFactory = new CXFWSManClientFactory();
    
    public static void main(String[] args) {
        new WSManCli().doMain(args);
    }

    public void doMain(String[] args) {
        ParserProperties parserProperties = ParserProperties.defaults()
                .withUsageWidth(120);

        CmdLineParser parser = new CmdLineParser(this, parserProperties);

        // args4j doesn't natively treat "--" as an end-of-options sentinel; everything
        // after it is routed verbatim into `arguments` so users can pass commands whose
        // own flags would otherwise collide with our options, e.g.
        //   -o SHELL -- powershell -Command "Get-Service"
        String[] forParser = args;
        List<String> trailingArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--".equals(args[i])) {
                forParser = Arrays.copyOfRange(args, 0, i);
                trailingArgs.addAll(Arrays.asList(args).subList(i + 1, args.length));
                break;
            }
        }

        try {
            parser.parseArgument(forParser);
        } catch( CmdLineException e ) {
            System.err.println("java -jar wsman4j.jar [options...] arguments...");
            parser.printUsage(System.err);
            System.err.println();
            e.printStackTrace();
            return;
        }

        // Anything after `--` is positional regardless of leading dashes
        arguments.addAll(trailingArgs);

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
        } else if (gssAuth) {
            builder.withGSSAuth();
        }
        WSManEndpoint endpoint = builder.build();
        LOG.info("Using endpoint: {}", endpoint);
        WSManClient client = clientFactory.getClient(endpoint);

        if (operation == WSManOperation.ENUM) {
            List<Node> nodes = new LinkedList<>();
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
        } else if (operation == WSManOperation.IDENTIFY) {
            LOG.info("Issuing IDENTIFY");
            Identity identity = client.identify();
            LOG.info("IDENTIFY successful: {}", identity);
        } else if (operation == WSManOperation.SHELL) {
            if (arguments.isEmpty()) {
                LOG.error("SHELL operation requires a command argument, e.g.: -o SHELL -- ipconfig /all");
                System.exit(2);
                return;
            }
            String executable = arguments.get(0);
            String[] commandArgs = arguments.size() > 1
                ? arguments.subList(1, arguments.size()).toArray(new String[0])
                : new String[0];
            LOG.info("Running WinRS command '{}' (timeout={}s)", executable, timeoutSeconds);
            CommandResult result = client.runCommand(executable, commandArgs, Duration.ofSeconds(timeoutSeconds));
            // Pass stdout/stderr through unmodified so the CLI is usable in shell pipelines.
            System.out.print(result.stdout());
            System.err.print(result.stderr());
            LOG.info("Command exited with code {}", result.exitCode());
            System.exit(result.exitCode());
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


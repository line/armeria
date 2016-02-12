/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.tomcat;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.startup.ContextConfig;
import org.apache.coyote.ProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;

final class ManagedTomcatServiceInvocationHandler extends TomcatServiceInvocationHandler {

    private static final Logger logger = LoggerFactory.getLogger(ManagedTomcatServiceInvocationHandler.class);

    private static final String ROOT_CONTEXT_PATH = "";

    /**
     * See {@link StandardServer#await()} for more information about this magic number (-2),
     * which is used for an embedded Tomcat server that manages its life cycle manually.
     */
    private static final int EMBEDDED_TOMCAT_PORT = -2;

    static {
        // Disable JNDI naming provided by Tomcat by default.
        System.setProperty("catalina.useNaming", "false");
    }

    private final TomcatServiceConfig config;
    private final ServerListener configurator = new Configurator();

    private volatile StandardServer server;

    private Server armeriaServer;

    ManagedTomcatServiceInvocationHandler(TomcatServiceConfig config) {
        super(config.hostname(), new Connector(TomcatProtocolHandler.class.getName()));
        this.config = requireNonNull(config, "config");
    }

    TomcatServiceConfig config() {
        return config;
    }

    @Override
    public void handlerAdded(ServiceConfig cfg) throws Exception {
        if (armeriaServer != null) {
            if (armeriaServer != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        armeriaServer = cfg.server();
        armeriaServer.addListener(configurator);
    }

    void start() {
        logger.info("Starting an embedded Tomcat: {}", config());

        assert server == null;

        // Create the connector with our protocol handler. Tomcat will call ProtocolHandler.setAdapter()
        // on its startup with the Coyote Adapter which gives an access to Tomcat's HTTP service pipeline.
        final Connector connector = connector();
        connector.setPort(0); // We do not really open a port - just trying to stop the Connector from complaining.
        final ProtocolHandler protocolHandler = connector.getProtocolHandler();

        server = newServer(connector, config());

        // Retrieve the components configured by newServer(), so we can use it in checkConfiguration().
        final Service service = server.findServices()[0];
        @SuppressWarnings("deprecation")
        final Engine engine = (Engine) service.getContainer();
        final StandardHost host = (StandardHost) engine.findChildren()[0];
        final Context context = (Context) host.findChildren()[0];

        // Apply custom configurators set via TomcatServiceBuilder.configurator()
        try {
            config().configurators().forEach(c -> c.accept(server));
        } catch (Throwable t) {
            throw new TomcatServiceException("failed to configure an embedded Tomcat", t);
        }

        // Make sure the configurators did not ruin what we have configured in this method.
        checkConfiguration(service, connector, engine, host, context);

        // Start the server finally.
        try {
            server.start();
        } catch (LifecycleException e) {
            throw new TomcatServiceException("failed to start an embedded Tomcat", e);
        }
    }

    void stop() {
        StandardServer server = this.server;
        this.server = null;

        if (server != null) {
            logger.info("Stopping an embedded Tomcat: {}", config());
            server.stopAwait();
        }
    }

    private StandardServer newServer(Connector connector, TomcatServiceConfig config) {
        //
        // server <------ services <------ engines <------ realm
        //                                         <------ hosts <------ contexts
        //                         <------ connectors
        //                         <------ executors
        //

        final StandardEngine engine = new StandardEngine();
        engine.setName(config.engineName());
        engine.setDefaultHost(config.hostname());
        engine.setRealm(config.realm());

        final StandardService service = new StandardService();
        service.setName(config.serviceName());
        service.setContainer(engine);

        service.addConnector(connector);

        final StandardServer server = new StandardServer();

        final File baseDir = config.baseDir().toFile();
        server.setCatalinaBase(baseDir);
        server.setCatalinaHome(baseDir);
        server.setPort(EMBEDDED_TOMCAT_PORT);

        server.addService(service);

        // Add the web application context.
        // Get or create a host.
        StandardHost host = (StandardHost) engine.findChild(config.hostname());
        if (host == null) {
            host = new StandardHost();
            host.setName(config.hostname());
            engine.addChild(host);
        }

        // Create a new context and add it to the host.
        final Context ctx;
        try {
            ctx = (Context) Class.forName(host.getContextClass(), true, getClass().getClassLoader()).newInstance();
        } catch (Exception e) {
            throw new TomcatServiceException("failed to create a new context: " + config, e);
        }

        ctx.setPath(ROOT_CONTEXT_PATH);
        ctx.setDocBase(config.docBase().toString());
        ctx.addLifecycleListener(TomcatUtil.getDefaultWebXmlListener());
        ctx.setConfigFile(TomcatUtil.getWebAppConfigFile(ROOT_CONTEXT_PATH, config.docBase()));

        final ContextConfig ctxCfg = new ContextConfig();
        ctxCfg.setDefaultWebXml(TomcatUtil.noDefaultWebXmlPath());
        ctx.addLifecycleListener(ctxCfg);

        host.addChild(ctx);

        return server;
    }

    private void checkConfiguration(Service expectedService, Connector expectedConnector,
                                    Engine expectedEngine, StandardHost expectedHost, Context expectedContext) {


        // Check if Catalina base and home directories have not been changed.
        final File expectedBaseDir = config.baseDir().toFile();
        if (!Objects.equals(server.getCatalinaBase(), expectedBaseDir) ||
            !Objects.equals(server.getCatalinaHome(), expectedBaseDir)) {
            throw new TomcatServiceException("A configurator should never change the Catalina base and home.");
        }

        // Check if the server's port has not been changed.
        if (server.getPort() != EMBEDDED_TOMCAT_PORT) {
            throw new TomcatServiceException("A configurator should never change the port of the server.");
        }

        // Check if the default service has not been removed and a new service has not been added.
        final Service[] services = server.findServices();
        if (services == null || services.length != 1 || services[0] != expectedService) {
            throw new TomcatServiceException(
                    "A configurator should never remove the default service or add a new service.");
        }

        // Check if the name of the default service has not been changed.
        if (!config().serviceName().equals(expectedService.getName())) {
            throw new TomcatServiceException(
                    "A configurator should never change the name of the default service.");
        }

        // Check if the default connector has not been removed
        final Connector[] connectors = expectedService.findConnectors();
        if (connectors == null || Arrays.stream(connectors).noneMatch(c -> c == expectedConnector)) {
            throw new TomcatServiceException("A configurator should never remove the default connector.");
        }

        // Check if the engine has not been changed.
        @SuppressWarnings("deprecation")
        final Container actualEngine = expectedService.getContainer();
        if (actualEngine != expectedEngine) {
            throw new TomcatServiceException(
                    "A configurator should never change the engine of the default service.");
        }

        // Check if the engine's name has not been changed.
        if (!config().engineName().equals(expectedEngine.getName())) {
            throw new TomcatServiceException(
                    "A configurator should never change the name of the default engine.");
        }

        // Check if the default realm has not been changed.
        if (expectedEngine.getRealm() != config().realm()) {
            throw new TomcatServiceException("A configurator should never change the default realm.");
        }

        // Check if the default host has not been removed.
        final Container[] engineChildren = expectedEngine.findChildren();
        if (engineChildren == null || Arrays.stream(engineChildren).noneMatch(c -> c == expectedHost)) {
            throw new TomcatServiceException("A configurator should never remove the default host.");
        }

        // Check if the default context has not been removed.
        final Container[] contextChildren = expectedHost.findChildren();
        if (contextChildren == null || Arrays.stream(contextChildren).noneMatch(c -> c == expectedContext)) {
            throw new TomcatServiceException("A configurator should never remove the default context.");
        }

        // Check if the docBase of the default context has not been changed.
        if (!config.docBase().toString().equals(expectedContext.getDocBase())) {
            throw new TomcatServiceException(
                    "A configurator should never change the docBase of the default context.");
        }
    }

    private final class Configurator extends ServerListenerAdapter {
        @Override
        public void serverStarting(Server server) {
            start();
        }

        @Override
        public void serverStopped(Server server) {
            stop();
        }
    }
}

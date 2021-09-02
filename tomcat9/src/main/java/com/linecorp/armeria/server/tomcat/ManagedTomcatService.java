/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.tomcat;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.catalina.Engine;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;

final class ManagedTomcatService extends TomcatService {
    private static final Logger logger = LoggerFactory.getLogger(ManagedTomcatService.class);

    private static final Set<LifecycleState> TOMCAT_START_STATES = Sets.immutableEnumSet(
            LifecycleState.STARTED, LifecycleState.STARTING, LifecycleState.STARTING_PREP);
    private final Function<String, Connector> connectorFactory;
    private final Consumer<Connector> postStopTask;
    private final ServerListener configurator;
    private static final Set<String> activeEngines = new HashSet<>();

    @Nullable
    private org.apache.catalina.Server server;
    @Nullable
    private Server armeriaServer;
    @Nullable
    private String hostName;
    @Nullable
    private Connector connector;
    @Nullable
    private String engineName;
    private boolean started;

    ManagedTomcatService(@Nullable String hostName,
                         Function<String, Connector> connectorFactory, Consumer<Connector> postStopTask) {
        this.hostName = hostName;
        this.connectorFactory = connectorFactory;
        this.postStopTask = postStopTask;
        configurator = new Configurator();
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        if (hostName == null) {
            hostName = cfg.server().defaultHostname();
        }

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

    void start() throws Exception {
        assert hostName() != null;
        started = false;
        connector = connectorFactory.apply(hostName());
        final Service service = connector.getService();

        final Engine engine = TomcatUtil.engine(service, hostName());
        final String engineName = engine.getName();

        if (activeEngines.contains(engineName)) {
            throw new TomcatServiceException("duplicate engine name: " + engineName);
        }

        server = service.getServer();

        if (!TOMCAT_START_STATES.contains(server.getState())) {
            logger.info("Starting an embedded Tomcat: {}", toString(server));
            server.start();
            started = true;
        } else {
            throw new TomcatServiceException("Cannot manage already running server: " + engineName);
        }

        activeEngines.add(engineName);
        this.engineName = engineName;
    }

    void stop() throws Exception {
        final org.apache.catalina.@Nullable Server server = this.server;
        @Nullable
        final Connector connector = this.connector;
        this.server = null;
        this.connector = null;

        if (engineName != null) {
            activeEngines.remove(engineName);
            engineName = null;
        }

        if (server == null || !started) {
            return;
        }

        try {
            logger.info("Stopping an embedded Tomcat: {}", toString(server));
            server.stop();
        } catch (Exception e) {
            logger.warn("Failed to stop an embedded Tomcat: {}", toString(server), e);
        }
        assert connector != null;
        postStopTask.accept(connector);
    }

    @Override
    public Connector connector() {
        return connector;
    }

    @Override
    String hostName() {
        return hostName;
    }

    private final class Configurator extends ServerListenerAdapter {
        @Override
        public void serverStarting(Server server) throws Exception {
            start();
        }

        @Override
        public void serverStopped(Server server) throws Exception {
            stop();
        }
    }
}

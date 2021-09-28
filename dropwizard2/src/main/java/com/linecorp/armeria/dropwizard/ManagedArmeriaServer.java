/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.dropwizard;

import static java.util.Objects.requireNonNull;

import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

import io.dropwizard.Configuration;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.server.ServerFactory;

/**
 * An Armeria {@link Server} wrapper class that accepts a Dropwizard Configuration
 * and initializes the Armeria {@link ServerBuilder} to be passed back to the
 * user via an {@link ArmeriaServerConfigurator}.
 */
class ManagedArmeriaServer<T extends Configuration> implements Managed {

    private static final Logger logger = LoggerFactory.getLogger(ManagedArmeriaServer.class);

    private final ArmeriaServerConfigurator serverConfigurator;
    @Nullable
    private Server server;
    private final @Valid ServerFactory serverFactory;

    /**
     * Creates a new instance.
     *
     * @param configuration The Dropwizard configuration
     * @param serverConfigurator A non-null implementation of {@link ArmeriaServerConfigurator}
     */
    ManagedArmeriaServer(T configuration, ArmeriaServerConfigurator serverConfigurator) {
        requireNonNull(configuration, "configuration");
        serverFactory = requireNonNull(configuration.getServerFactory(), "server");
        if (!(serverFactory instanceof ArmeriaServerFactory)) {
            throw new RuntimeException("Cannot manage Armeria Server " +
                                       "unless Configuration server.type=" + ArmeriaServerFactory.TYPE);
        }
        this.serverConfigurator = requireNonNull(serverConfigurator, "serverConfigurator");
    }

    @Override
    public void start() throws Exception {
        logger.trace("Getting Armeria Server Builder");
        final ServerBuilder sb = ((ArmeriaServerFactory) serverFactory).getServerBuilder();
        logger.trace("Calling Server Configurator");
        serverConfigurator.configure(sb);
        server = sb.build();
        if (logger.isDebugEnabled()) {
            logger.debug("Built server {}", server);
        }
        logger.info("Starting Armeria Server");
        try {
            server.start().join();
        } catch (Throwable cause) {
            Exceptions.throwUnsafely(Exceptions.peel(cause));
        }
        logger.info("Started Armeria Server");
    }

    @Override
    public void stop() throws Exception {
        if (server != null) {
            logger.info("Stopping Armeria Server");
            server.stop().thenRunAsync(() -> logger.info("Stopped Armeria Server"));
        }
    }
}

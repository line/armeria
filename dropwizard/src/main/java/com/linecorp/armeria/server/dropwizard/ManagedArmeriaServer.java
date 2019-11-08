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
package com.linecorp.armeria.server.dropwizard;

import java.util.Objects;

import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

import io.dropwizard.Configuration;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.server.ServerFactory;

public class ManagedArmeriaServer<T extends Configuration> implements Managed {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedArmeriaServer.class);

    private final @Valid T configuration;
    private final BuilderCallback builderCallback;
    private Server server;

    /**
    * An Armeria {@link Server} wrapper class that accepts a Dropwizard Configuration
    * and initializes the Armeria {@link ServerBuilder} to be passed back to the
    * user via a {@link BuilderCallback}.
    *
    * @param configuration The Dropwizard configuration
    * @param builderCallback A non-null implementation of {@link BuilderCallback}
    */
    public ManagedArmeriaServer(final T configuration,
                                final BuilderCallback builderCallback) {
        this.configuration = configuration;
        this.builderCallback = Objects.requireNonNull(builderCallback, "builderCallback");
    }

    @Override
    public void start() throws Exception {
        LOGGER.trace("Getting Armeria Server Builder");
        final ServerFactory serverFactory = configuration.getServerFactory();
        if (!(serverFactory instanceof ArmeriaServerFactory)) {
            throw new RuntimeException("Cannot manage Armeria Server " +
                    "unless Configuration server.type=" + ArmeriaServerFactory.TYPE);
        }
        final ServerBuilder sb = ((ArmeriaServerFactory) serverFactory).getServerBuilder();
        LOGGER.trace("Calling Builder Callback");
        builderCallback.onServerBuilderReady(sb);
        server = sb.build();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Built server {}", server);
        }
        LOGGER.info("Starting Armeria Server");
        server.start().thenRunAsync(() -> LOGGER.info("Started Armeria Server"));
    }

    @Override
    public void stop() throws Exception {
        if (server != null) {
            LOGGER.info("Stopping Armeria Server");
            server.stop().thenRunAsync(() -> LOGGER.info("Stopped Armeria Server"));
        }
    }

    public interface BuilderCallback {
        void onServerBuilderReady(ServerBuilder builder);
    }
}

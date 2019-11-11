/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.server.consul;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.consul.Check;
import com.linecorp.armeria.internal.consul.ConsulClient;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServerPort;

/**
 * A Consul Server Listener. When you add this listener, server will be automatically registered
 * into the Consul.
 */
public class ConsulUpdatingListener extends ServerListenerAdapter {

    public static final String DEFAULT_CHECK_INTERVAL = "10s";
    private static final Logger logger = LoggerFactory.getLogger(ConsulUpdatingListener.class);

    /**
     * Creates a Consul server listener, which registers server into Consul.
     *
     * <p>If you need a fully customized {@link ConsulUpdatingListener} instance, use
     * {@link ConsulUpdatingListenerBuilder} instead.
     *
     * @param consulUrl    Consul connection string
     * @param serviceName  Consul node path(under which this server will be registered)
     */
    public static ConsulUpdatingListener of(String consulUrl, String serviceName) {
        return builder(serviceName).url(consulUrl).build();
    }

    /**
     * Creates a {@code ConsulUpdatingListenerBuilder} to build {@code ConsulUpdatingListener}.
     * @param serviceName A service name which registers into Consul.
     * @return A builder for {@code ConsulUpdatingListener}.
     */
    public static ConsulUpdatingListenerBuilder builder(String serviceName) {
        return new ConsulUpdatingListenerBuilder(serviceName);
    }

    private final ConsulClient client;
    private final String serviceName;
    @Nullable
    private Check check;
    @Nullable
    private String serviceId;
    @Nullable
    private Endpoint endpoint;

    ConsulUpdatingListener(ConsulClient client, String serviceName, @Nullable Endpoint endpoint,
                           @Nullable String checkUrl, @Nullable String checkMethod,
                           @Nullable String checkInterval) {
        this.client = requireNonNull(client, "client");
        this.serviceName = requireNonNull(serviceName, "serviceName");
        this.endpoint = endpoint;
        if (checkUrl != null) {
            final Check check = new Check();
            check.setHttp(checkUrl);
            check.setMethod(checkMethod);
            check.setInterval(checkInterval == null ? DEFAULT_CHECK_INTERVAL
                                                    : checkInterval);
            this.check = check;
        }
    }

    @Override
    public void serverStarted(Server server) throws Exception {
        if (endpoint == null) {
            final ServerPort activePort = server.activePort();
            if (activePort == null) {
                throw new IllegalStateException("Can not get activePort for server: " + server);
            }
            endpoint = Endpoint.of(activePort.localAddress().getHostString(),
                                   activePort.localAddress().getPort());
        }
        client.register(serviceName, endpoint, check).thenAccept(id -> {
            serviceId = id;
            logger.trace("registered: {}", id);
        });
    }

    @Override
    public void serverStopping(Server server) {
        if (serviceId != null) {
            client.deregister(serviceId)
                    .thenAccept(a -> logger.trace("deregistered: {}", serviceId));
        }
    }
}

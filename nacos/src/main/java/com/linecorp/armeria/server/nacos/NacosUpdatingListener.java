/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.server.nacos;

import static java.util.Objects.requireNonNull;

import java.net.Inet4Address;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.nacos.NacosClient;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServerPort;

/**
 * A {@link ServerListener} which registers the current {@link Server} to
 * <a href="https://nacos.io">Nacos</a>.
 */
public class NacosUpdatingListener extends ServerListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(NacosUpdatingListener.class);

    /**
     * Returns a newly-created {@link NacosUpdatingListenerBuilder} with the specified {@code nacosUri}
     * and {@code serviceName} to build {@link NacosUpdatingListener}.
     *
     * @param nacosUri the URI of Nacos API service
     * @param serviceName the service name which is registered into Nacos.
     */
    public static NacosUpdatingListenerBuilder builder(URI nacosUri, String serviceName) {
        return new NacosUpdatingListenerBuilder(nacosUri, serviceName);
    }

    private final NacosClient nacosClient;

    private final String serviceName;

    @Nullable
    private final Endpoint endpoint;

    @Nullable
    private final String namespaceId;

    @Nullable
    private final String groupName;

    @Nullable
    private final String clusterName;

    @Nullable
    private final String app;

    private volatile boolean isRegistered;

    NacosUpdatingListener(NacosClient nacosClient, String serviceName, @Nullable Endpoint endpoint,
                          @Nullable String namespaceId, @Nullable String groupName,
                          @Nullable String clusterName, @Nullable String app) {
        this.nacosClient = requireNonNull(nacosClient, "nacosClient");
        this.serviceName = requireNonNull(serviceName, "serviceName");
        this.endpoint = endpoint;
        this.namespaceId = namespaceId;
        this.groupName = groupName;
        this.clusterName = clusterName;
        this.app = app;
    }

    @Override
    public void serverStarted(Server server) throws Exception {
        final Endpoint endpoint = getEndpoint(server);
        nacosClient.register(serviceName, endpoint, namespaceId, groupName, clusterName, app)
                .aggregate()
                .handle((res, cause) -> {
                    if (cause != null) {
                        logger.warn("Failed to register {}:{} to Nacos: {}",
                                    endpoint.host(), endpoint.port(), nacosClient.uri(), cause);
                        return null;
                    }

                    if (res.status() != HttpStatus.OK) {
                        logger.warn("Failed to register {}:{} to Nacos: {} (status: {}, content: {})",
                                    endpoint.host(), endpoint.port(), nacosClient.uri(), res.status(),
                                    res.contentUtf8());
                        return null;
                    }

                    logger.info("Registered {}:{} to Nacos: {}",
                                endpoint.host(), endpoint.port(), nacosClient.uri());
                    isRegistered = true;
                    return null;
                });
    }

    private Endpoint getEndpoint(Server server) {
        if (endpoint != null) {
            if (endpoint.hasPort()) {
                warnIfInactivePort(server, endpoint.port());
            }
            return endpoint;
        }
        return defaultEndpoint(server);
    }

    private static Endpoint defaultEndpoint(Server server) {
        final ServerPort serverPort = server.activePort();
        assert serverPort != null;

        final Inet4Address inet4Address = SystemInfo.defaultNonLoopbackIpV4Address();
        final String host = inet4Address != null ? inet4Address.getHostAddress() : server.defaultHostname();
        return Endpoint.of(host, serverPort.localAddress().getPort());
    }

    private static void warnIfInactivePort(Server server, int port) {
        for (ServerPort serverPort : server.activePorts().values()) {
            if (serverPort.localAddress().getPort() == port) {
                return;
            }
        }
        logger.warn("The specified port number {} does not exist. (expected one of activePorts: {})",
                    port, server.activePorts());
    }

    @Override
    public void serverStopping(Server server) {
        final Endpoint endpoint = getEndpoint(server);
        if (isRegistered) {
            nacosClient.deregister(serviceName, endpoint, namespaceId, groupName, clusterName, app)
                    .aggregate()
                    .handle((res, cause) -> {
                        if (cause != null) {
                            logger.warn("Failed to deregister {}:{} from Nacos: {}",
                                        endpoint.ipAddr(), endpoint.port(), nacosClient.uri(), cause);
                        } else if (res.status() != HttpStatus.OK) {
                            logger.warn("Failed to deregister {}:{} from Nacos: {}. (status: {}, content: {})",
                                        endpoint.ipAddr(), endpoint.port(), nacosClient.uri(), res.status(),
                                        res.contentUtf8());
                        }
                        return null;
                    });
        }
    }
}

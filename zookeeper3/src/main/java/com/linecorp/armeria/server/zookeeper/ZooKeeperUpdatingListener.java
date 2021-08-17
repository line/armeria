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
package com.linecorp.armeria.server.zookeeper;

import static java.util.Objects.requireNonNull;

import java.net.Inet4Address;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.zookeeper.ZooKeeperEndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.zookeeper.ServerSetsInstance;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServerPort;

/**
 * A {@link ServerListener} which registers the current {@link Server} to
 * <a href="https://zookeeper.apache.org/">ZooKeeper</a> as an ephemeral node. When server stops, or a network
 * partition occurs, the underlying ZooKeeper session will be closed, and the node will be automatically
 * removed. As a result, the clients that use a {@link ZooKeeperEndpointGroup} will be notified, and they will
 * update their endpoint list automatically so that they do not attempt to connect to the unreachable servers.
 */
public final class ZooKeeperUpdatingListener extends ServerListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperUpdatingListener.class);

    /**
     * Creates a ZooKeeper server listener, which registers the {@link Server} into ZooKeeper.
     *
     * <p>If you need a fully customized {@link ZooKeeperUpdatingListener} instance, use
     * {@link #builder(String, String, ZooKeeperRegistrationSpec)} instead.
     *
     * @param zkConnectionStr the ZooKeeper connection string
     * @param znodePath the ZooKeeper node to register
     * @param spec the {@link ZooKeeperRegistrationSpec} to encode and register the {@link Server}
     */
    public static ZooKeeperUpdatingListener of(
            String zkConnectionStr, String znodePath, ZooKeeperRegistrationSpec spec) {
        return builder(zkConnectionStr, znodePath, spec).build();
    }

    /**
     * Creates a ZooKeeper server listener, which registers the {@link Server} into ZooKeeper.
     *
     * <p>If you need a fully customized {@link ZooKeeperUpdatingListener} instance, use
     * {@link #builder(CuratorFramework, String, ZooKeeperRegistrationSpec)} instead.
     *
     * @param client the curator framework instance
     * @param znodePath the ZooKeeper node to register
     * @param spec the {@link ZooKeeperRegistrationSpec} to encode and register the {@link Server}
     */
    public static ZooKeeperUpdatingListener of(
            CuratorFramework client, String znodePath, ZooKeeperRegistrationSpec spec) {
        return builder(client, znodePath, spec).build();
    }

    /**
     * Returns a {@link ZooKeeperUpdatingListenerBuilder} with a {@link CuratorFramework} instance and a znode
     * path.
     *
     * @param client the curator framework instance
     * @param znodePath the ZooKeeper node to register
     * @param spec the {@link ZooKeeperRegistrationSpec} to encode and register the {@link Server}
     */
    public static ZooKeeperUpdatingListenerBuilder builder(
            CuratorFramework client, String znodePath, ZooKeeperRegistrationSpec spec) {
        return new ZooKeeperUpdatingListenerBuilder(client, znodePath, spec);
    }

    /**
     * Returns a {@link ZooKeeperUpdatingListenerBuilder} with a ZooKeeper connection string and a znode path.
     *
     * @param zkConnectionStr the ZooKeeper connection string
     * @param znodePath the ZooKeeper node to register
     * @param spec the {@link ZooKeeperRegistrationSpec} to encode and register the {@link Server}
     */
    public static ZooKeeperUpdatingListenerBuilder builder(
            String zkConnectionStr, String znodePath, ZooKeeperRegistrationSpec spec) {
        return new ZooKeeperUpdatingListenerBuilder(zkConnectionStr, znodePath, spec);
    }

    private final CuratorFramework client;
    private final String znodePath;
    private final ZooKeeperRegistrationSpec spec;
    private final boolean closeClientOnStop;

    ZooKeeperUpdatingListener(CuratorFramework client, String znodePath, ZooKeeperRegistrationSpec spec,
                              boolean closeClientOnStop) {
        this.client = requireNonNull(client, "client");
        this.znodePath = requireNonNull(znodePath, "znodePath");
        this.spec = spec;
        this.closeClientOnStop = closeClientOnStop;
    }

    @Override
    public void serverStarted(Server server) throws Exception {
        final ZooKeeperRegistrationSpec registrationSpec = fillAndCreateNewRegistrationSpec(spec, server);
        if (client.getState() != CuratorFrameworkState.STARTED) {
            client.start();
        }
        client.create()
              .creatingParentsIfNeeded()
              .withMode(registrationSpec.isSequential() ? CreateMode.EPHEMERAL_SEQUENTIAL
                                                        : CreateMode.EPHEMERAL)
              .forPath(znodePath + registrationSpec.path(), registrationSpec.encodedInstance());
    }

    private static ZooKeeperRegistrationSpec fillAndCreateNewRegistrationSpec(
            ZooKeeperRegistrationSpec spec, Server server) {
        if (spec instanceof LegacyZooKeeperRegistrationSpec) {
            return legacySpec(spec, server);
        }
        if (spec instanceof CuratorRegistrationSpec) {
            return curatorSpec(((CuratorRegistrationSpec) spec).serviceInstance(), server);
        }
        if (spec instanceof ServerSetsRegistrationSpec) {
            return serverSetsSpec((ServerSetsRegistrationSpec) spec, server);
        }
        return spec;
    }

    private static ZooKeeperRegistrationSpec legacySpec(ZooKeeperRegistrationSpec spec, Server server) {
        final Endpoint endpoint = ((LegacyZooKeeperRegistrationSpec) spec).endpoint();
        if (endpoint != null) {
            if (endpoint.hasPort()) {
                warnIfInactivePort(server, endpoint.port(), null);
                return spec;
            }
            final ServerPort serverPort = server.activePort();
            assert serverPort != null;
            return ZooKeeperRegistrationSpec.legacy(endpoint.withPort(serverPort.localAddress().getPort()));
        }

        return ZooKeeperRegistrationSpec.legacy(defaultEndpoint(server));
    }

    private static Endpoint defaultEndpoint(Server server) {
        final ServerPort serverPort = server.activePort();
        assert serverPort != null;
        return Endpoint.of(defaultAddress(server), serverPort.localAddress().getPort());
    }

    private static String defaultAddress(Server server) {
        final Inet4Address inet4Address = SystemInfo.defaultNonLoopbackIpV4Address();
        return inet4Address != null ? inet4Address.getHostAddress() : server.defaultHostname();
    }

    private static ZooKeeperRegistrationSpec curatorSpec(
            ServiceInstance<?> serviceInstance, Server server) {
        final CuratorRegistrationSpecBuilder builder =
                ZooKeeperRegistrationSpec.builderForCurator(serviceInstance.getName());
        builder.serviceId(serviceInstance.getId());
        final String address;
        if (serviceInstance.getAddress() != null) {
            address = serviceInstance.getAddress();
        } else {
            address = defaultAddress(server);
        }
        builder.serviceAddress(address);
        final int port = port(server, SessionProtocol.HTTP, serviceInstance.getPort());
        if (port > 0) {
            builder.port(port);
        }
        final int sslPort = port(server, SessionProtocol.HTTPS, serviceInstance.getSslPort());
        if (sslPort > 0) {
            builder.sslPort(sslPort);
        }
        builder.serviceType(serviceInstance.getServiceType());
        final Object payload = serviceInstance.getPayload();
        if (payload != null) {
            builder.payload(payload);
        }
        return builder.build();
    }

    private static ZooKeeperRegistrationSpec serverSetsSpec(
            ServerSetsRegistrationSpec spec, Server server) {
        final ServerSetsInstance serverSetsInstance = spec.serverSetsInstance();
        if (serverSetsInstance.serviceEndpoint() != null) {
            warnIfInactivePort(server, serverSetsInstance.serviceEndpoint().port(), null);
            return spec;
        }
        final ServerSetsRegistrationSpecBuilder builder =
                ZooKeeperRegistrationSpec.builderForServerSets();
        builder.serviceEndpoint(defaultEndpoint(server))
               .additionalEndpoints(serverSetsInstance.additionalEndpoints())
               .metadata(serverSetsInstance.metadata())
               .sequential(spec.isSequential())
               .nodeName(spec.path().substring(1)); // Simply remove prepended '/'.
        final Integer shardId = serverSetsInstance.shardId();
        if (shardId != null) {
            builder.shardId(shardId);
        }
        return builder.build();
    }

    private static int port(Server server, SessionProtocol protocol, @Nullable Integer port) {
        if (port != null) {
            warnIfInactivePort(server, port, protocol);
            return port;
        }
        final ServerPort serverPort = server.activePort(protocol);
        return serverPort != null ? serverPort.localAddress().getPort() : -1;
    }

    private static void warnIfInactivePort(
            Server server, int port, @Nullable SessionProtocol protocol) {
        for (ServerPort serverPort : server.activePorts().values()) {
            if ((protocol == null || serverPort.hasProtocol(protocol)) &&
                serverPort.localAddress().getPort() == port) {
                return;
            }
        }
        logger.warn("The specified port number {} does not exist. (expected one of activePorts: {})",
                    port, server.activePorts());
    }

    @Override
    public void serverStopping(Server server) {
        if (closeClientOnStop) {
            client.close();
        }
    }
}

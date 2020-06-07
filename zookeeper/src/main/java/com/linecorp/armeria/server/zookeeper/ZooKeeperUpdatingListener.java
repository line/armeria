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

import javax.annotation.Nullable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.zookeeper.ZooKeeperEndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.SystemInfo;
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
     * {@link #builder(String, String, ZookeeperRegistrationSpec)} instead.
     *
     * @param zkConnectionStr the ZooKeeper connection string
     * @param zNodePath the ZooKeeper node to register
     * @param spec the {@link ZookeeperRegistrationSpec} to encode and register the {@link Server}
     */
    public static ZooKeeperUpdatingListener of(
            String zkConnectionStr, String zNodePath, ZookeeperRegistrationSpec spec) {
        return builder(zkConnectionStr, zNodePath, spec).build();
    }

    /**
     * Creates a ZooKeeper server listener, which registers the {@link Server} into ZooKeeper.
     *
     * <p>If you need a fully customized {@link ZooKeeperUpdatingListener} instance, use
     * {@link #builder(CuratorFramework, String, ZookeeperRegistrationSpec)} instead.
     *
     * @param client the curator framework instance
     * @param zNodePath the ZooKeeper node to register
     * @param spec the {@link ZookeeperRegistrationSpec} to encode and register the {@link Server}
     */
    public static ZooKeeperUpdatingListener of(
            CuratorFramework client, String zNodePath, ZookeeperRegistrationSpec spec) {
        return builder(client, zNodePath, spec).build();
    }

    /**
     * Returns a {@link ZooKeeperUpdatingListenerBuilder} with a {@link CuratorFramework} instance and a zNode
     * path.
     *
     * @param client the curator framework instance
     * @param zNodePath the ZooKeeper node to register
     * @param spec the {@link ZookeeperRegistrationSpec} to encode and register the {@link Server}
     */
    public static ZooKeeperUpdatingListenerBuilder builder(
            CuratorFramework client, String zNodePath, ZookeeperRegistrationSpec spec) {
        return new ZooKeeperUpdatingListenerBuilder(client, zNodePath, spec);
    }

    /**
     * Returns a {@link ZooKeeperUpdatingListenerBuilder} with a ZooKeeper connection string and a zNode path.
     *
     * @param zkConnectionStr the ZooKeeper connection string
     * @param zNodePath the ZooKeeper node to register
     * @param spec the {@link ZookeeperRegistrationSpec} to encode and register the {@link Server}
     */
    public static ZooKeeperUpdatingListenerBuilder builder(
            String zkConnectionStr, String zNodePath, ZookeeperRegistrationSpec spec) {
        return new ZooKeeperUpdatingListenerBuilder(zkConnectionStr, zNodePath, spec);
    }

    private final CuratorFramework client;
    private final String zNodePath;
    private final ZookeeperRegistrationSpec spec;
    private final boolean closeClientOnStop;

    ZooKeeperUpdatingListener(CuratorFramework client, String zNodePath, ZookeeperRegistrationSpec spec,
                              boolean closeClientOnStop) {
        this.client = requireNonNull(client, "client");
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        this.spec = spec;
        this.closeClientOnStop = closeClientOnStop;
    }

    @Override
    public void serverStarted(Server server) throws Exception {
        final ZookeeperRegistrationSpec registrationSpec = fillAndCreateNewRegistrationSpec(spec, server);
        client.start();
        client.create()
              .creatingParentsIfNeeded()
              .withMode(CreateMode.EPHEMERAL)
              .forPath(zNodePath + registrationSpec.path(), registrationSpec.encodedInstance());
    }

    private static ZookeeperRegistrationSpec fillAndCreateNewRegistrationSpec(
            ZookeeperRegistrationSpec spec, Server server) {
        if (spec instanceof LegacyZookeeperRegistrationSpec) {
            final Endpoint endpoint = ((LegacyZookeeperRegistrationSpec) spec).endpoint();
            if (endpoint.hasPort() && validatePort(server, endpoint.port(), null)) {
                return spec;
            }
            final ServerPort serverPort = server.activePort();
            assert serverPort != null;
            return ZookeeperRegistrationSpec.legacy(endpoint.withPort(serverPort.localAddress().getPort()));
        } else if (spec instanceof CuratorRegistrationSpec) {
            final ServiceInstance<?> serviceInstance =
                    ((CuratorRegistrationSpec) spec).serviceInstance();
            return fillAndCreateNewRegistrationSpec(serviceInstance, server);
        } else {
            return spec;
        }
    }

    private static ZookeeperRegistrationSpec fillAndCreateNewRegistrationSpec(
            ServiceInstance<?> serviceInstance, Server server) {
        final CuratorRegistrationSpecBuilder builder =
                ZookeeperRegistrationSpec.builderForCurator(serviceInstance.getName());
        builder.serviceId(serviceInstance.getId());
        final String address;
        if (serviceInstance.getAddress() != null) {
            address = serviceInstance.getAddress();
        } else {
            final Inet4Address inet4Address = SystemInfo.defaultNonLoopbackIpV4Address();
            address = inet4Address != null ? inet4Address.getHostAddress() : server.defaultHostname();
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

    private static int port(Server server, SessionProtocol protocol, @Nullable Integer port) {
        if (port != null) {
            if (validatePort(server, port, protocol)) {
                return port;
            }
        }
        final ServerPort serverPort = server.activePort(protocol);
        return serverPort != null ? serverPort.localAddress().getPort() : -1;
    }

    private static boolean validatePort(Server server, int port, @Nullable SessionProtocol protocol) {
        for (ServerPort serverPort : server.activePorts().values()) {
            if ((protocol == null || serverPort.hasProtocol(protocol)) &&
                serverPort.localAddress().getPort() == port) {
                return true;
            }
        }
        logger.warn("The specified port number {} does not exist. (expected one of activePorts: {})",
                    port, server.activePorts());
        return false;
    }

    @Override
    public void serverStopping(Server server) {
        if (closeClientOnStop) {
            client.close();
        }
    }
}

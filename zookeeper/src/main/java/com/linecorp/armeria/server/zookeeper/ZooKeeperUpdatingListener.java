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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.zookeeper.CreateMode;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.zookeeper.ZooKeeperEndpointGroup;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.internal.common.zookeeper.ZooKeeperDefaults;
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

    /**
     * Returns a {@link ZooKeeperUpdatingListenerBuilder} with a {@link CuratorFramework} instance and a zNode
     * path.
     *
     * @param client the curator framework instance
     * @param zNodePath the ZooKeeper node to register
     */
    public static ZooKeeperUpdatingListenerBuilder builder(CuratorFramework client, String zNodePath) {
        return new ZooKeeperUpdatingListenerBuilder(client, zNodePath);
    }

    /**
     * Returns a {@link ZooKeeperUpdatingListenerBuilder} with a ZooKeeper connection string and a zNode path.
     *
     * @param connectionStr the ZooKeeper connection string
     * @param zNodePath the ZooKeeper node to register
     */
    public static ZooKeeperUpdatingListenerBuilder builder(String connectionStr, String zNodePath) {
        return new ZooKeeperUpdatingListenerBuilder(connectionStr, zNodePath);
    }

    /**
     * Creates a ZooKeeper server listener, which registers server into ZooKeeper.
     *
     * <p>If you need a fully customized {@link ZooKeeperUpdatingListener} instance, use
     * {@link #builder(String, String)} instead.
     *
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will be registered)
     */
    public static ZooKeeperUpdatingListener of(String zkConnectionStr, String zNodePath) {
        return builder(zkConnectionStr, zNodePath).build();
    }

    private final CuratorFramework client;
    private final String zNodePath;
    private final NodeValueCodec nodeValueCodec;
    @Nullable
    private Endpoint endpoint;
    private final boolean closeClientOnStop;

    ZooKeeperUpdatingListener(CuratorFramework client, String zNodePath, NodeValueCodec nodeValueCodec,
                              @Nullable Endpoint endpoint, boolean closeClientOnStop) {
        this.client = requireNonNull(client, "client");
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "nodeValueCodec");
        this.endpoint = endpoint;
        this.closeClientOnStop = closeClientOnStop;
    }

    /**
     * A ZooKeeper server listener, which registers server into ZooKeeper.
     *
     * @deprecated Use {@link ZooKeeperUpdatingListenerBuilder}.
     *
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will be registered)
     * @param sessionTimeout  session timeout
     * @param endpoint        the endpoint of the server being registered
     */
    @Deprecated
    public ZooKeeperUpdatingListener(String zkConnectionStr, String zNodePath, int sessionTimeout,
                                     @Nullable Endpoint endpoint) {
        requireNonNull(zkConnectionStr, "zkConnectionStr");
        checkArgument(!zkConnectionStr.isEmpty(), "zkConnectionStr can't be empty");
        client = CuratorFrameworkFactory.builder()
                                        .connectString(zkConnectionStr)
                                        .retryPolicy(ZooKeeperDefaults.DEFAULT_RETRY_POLICY)
                                        .sessionTimeoutMs(sessionTimeout)
                                        .build();
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        nodeValueCodec = NodeValueCodec.ofDefault();
        this.endpoint = endpoint;
        closeClientOnStop = true;
    }

    /**
     * A ZooKeeper server listener, which registers server into ZooKeeper.
     *
     * @deprecated Use {@link ZooKeeperUpdatingListenerBuilder}.
     *
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will be registered)
     * @param sessionTimeout  session timeout
     */
    @Deprecated
    public ZooKeeperUpdatingListener(String zkConnectionStr, String zNodePath, int sessionTimeout) {
        this(zkConnectionStr, zNodePath, sessionTimeout, null);
    }

    @Override
    public void serverStarted(Server server) throws Exception {
        if (endpoint == null) {
            final ServerPort activePort = server.activePort();
            assert activePort != null;
            endpoint = Endpoint.of(server.defaultHostname(),
                                   activePort.localAddress().getPort());
        }
        client.start();
        final String key = endpoint.host() + '_' + endpoint.port();
        final byte[] value = nodeValueCodec.encode(endpoint);
        client.create()
              .creatingParentsIfNeeded()
              .withMode(CreateMode.EPHEMERAL)
              .forPath(zNodePath + '/' + key, value);
    }

    @Override
    public void serverStopping(Server server) {
        if (closeClientOnStop) {
            client.close();
        }
    }
}

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
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.internal.zookeeper.ZooKeeperDefaults;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListenerAdapter;

/**
 * A ZooKeeper Server Listener. When you add this listener, server will be automatically registered
 * into the ZooKeeper.
 */
public class ZooKeeperUpdatingListener extends ServerListenerAdapter {

    /**
     * Creates a ZooKeeper server listener, which registers server into ZooKeeper.
     *
     * <p>If you need a fully customized {@link ZooKeeperUpdatingListener} instance, use
     * {@link ZooKeeperUpdatingListenerBuilder} instead.
     *
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will be registered)
     */
    public static ZooKeeperUpdatingListener of(String zkConnectionStr, String zNodePath) {
        return new ZooKeeperUpdatingListenerBuilder(zkConnectionStr, zNodePath).build();
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
        nodeValueCodec = NodeValueCodec.DEFAULT;
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
            assert server.activePort().isPresent();
            endpoint = Endpoint.of(server.defaultHostname(),
                                   server.activePort().get()
                                         .localAddress().getPort());
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

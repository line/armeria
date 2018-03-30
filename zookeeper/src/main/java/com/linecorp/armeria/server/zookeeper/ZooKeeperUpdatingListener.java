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

import com.google.common.base.Strings;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.internal.zookeeper.Constants;
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
        return new ZooKeeperUpdatingListenerBuilder(zkConnectionStr, zNodePath)
                .build();
    }

    private final CuratorFramework client;
    private final String zNodePath;
    private final NodeValueCodec nodeValueCodec;
    @Nullable
    private Endpoint endpoint;
    private final boolean internalClient;

    ZooKeeperUpdatingListener(CuratorFramework client, String zNodePath, NodeValueCodec nodeValueCodec,
                              @Nullable Endpoint endpoint, boolean internalClient) {
        this.client = requireNonNull(client, "client");
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        this.nodeValueCodec = requireNonNull(nodeValueCodec);
        this.endpoint = endpoint;
        this.internalClient = internalClient;
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
                                     Endpoint endpoint) {
        checkArgument(!Strings.isNullOrEmpty(zkConnectionStr), "zkConnectionStr");
        client = CuratorFrameworkFactory.builder()
                                        .connectString(zkConnectionStr)
                                        .retryPolicy(Constants.DEFAULT_RETRY_POLICY)
                                        .sessionTimeoutMs(sessionTimeout)
                                        .build();
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        this.nodeValueCodec = NodeValueCodec.DEFAULT;
        this.endpoint = endpoint;
        this.internalClient = true;
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
    public void serverStarted(Server server) {
        if (endpoint == null) {
            assert server.activePort().isPresent();
            endpoint = Endpoint.of(server.defaultHostname(),
                                   server.activePort().get()
                                         .localAddress().getPort());
        }
        client.start();
        String key = endpoint.host() + '_' + endpoint.port();
        byte[] value = nodeValueCodec.encode(endpoint);
        try {
            client.create()
                  .creatingParentsIfNeeded()
                  .withMode(CreateMode.EPHEMERAL)
                  .forPath(zNodePath + '/' + key, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void serverStopping(Server server) {
        if (internalClient) {
            client.close();
        }
    }
}

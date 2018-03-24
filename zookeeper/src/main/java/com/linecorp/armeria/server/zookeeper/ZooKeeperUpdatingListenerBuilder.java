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
package com.linecorp.armeria.server.zookeeper;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.server.ServerListener;

/**
 * Builds a new {@link ServerListener}, which registers the server to the zookeeper instance.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerListener register =
 * // Zookeeper Connection String and Zookeeper Node to use
 * new ZooKeeperUpdatingListenerBuilder("myZooKeeperHost:2181", "/myProductionEndpoints")
 * // Session Timeout
 * .sessionTimeOut(10000)
 * // NodeValueCodec
 * .nodeValueCodec(NodeValueCodec.DEFAULT)
 * .build();
 * // Set to `Server`.
 * Server server = ...
 * server.serverListener(register);
 * }</pre>
 *
 * <p>You can also specify the {@link CuratorFramework} instance to use. In this case,
 * {@link #connectTimeout(int)} and {@link #sessionTimeout(int)} will be ignored.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // CuratorFramework instance and Zookeeper Node to use
 * ServerListener register = new ZooKeeperUpdatingListenerBuilder(curatorFramework, "/myProductionEndpoints")
 * // NodeValueCodec
 * .nodeValueCodec(NodeValueCodec.DEFAULT)
 * .build();
 * // Set to `Server`.
 * Server server = ...
 * server.serverListener(register);
 * }</pre>
 * */
public final class ZooKeeperUpdatingListenerBuilder {
    private static final int DEFAULT_CONNECT_TIMEOUT = 1000;
    private static final int DEFAULT_SESSION_TIMEOUT = 10000;

    private CuratorFramework client;
    private String connect;
    private String node;
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int sessionTimeout = DEFAULT_SESSION_TIMEOUT;
    private Endpoint endpoint;
    private NodeValueCodec nodeValueCodec = NodeValueCodec.DEFAULT;

    /**
     * Creates a {@link ZooKeeperUpdatingListenerBuilder} with a {@link CuratorFramework} instance and a zNode.
     *
     * @param client the curator framework instance.
     */
    public ZooKeeperUpdatingListenerBuilder(CuratorFramework client, String node) {
        this.client = requireNonNull(client, "client");
        checkArgument(!isNullOrEmpty(node), "node");
        this.node = node;
    }

    /**
     * Creates a {@link ZooKeeperUpdatingListenerBuilder} with a Zookeeper Connection String and a zNode.
     *
     * @param connect the zookeeper connection string.
     * @param node the zookeeper node to register.
     */
    public ZooKeeperUpdatingListenerBuilder(String connect, String node) {
        checkArgument(!isNullOrEmpty(connect), "connect");
        checkArgument(!isNullOrEmpty(node), "node");
        this.connect = connect;
        this.node = node;
    }

    /**
     * Sets the connect timeout (in ms). (default: 1000)
     *
     * <p>Ignored when {@link CuratorFramework} is set. (see: {@link #client})
     *
     * @param timeout the connect timeout.
     */
    public ZooKeeperUpdatingListenerBuilder connectTimeout(int timeout) {
        checkArgument(timeout > 0, "timeout: %s (expected: > 0)", timeout);
        this.connectTimeout = timeout;
        return this;
    }

    /**
     * Sets the session timeout (in ms). (default: 10000)
     *
     * <p>Ignored when {@link CuratorFramework} is set. (see: {@link #client})
     *
     * @param timeout the session timeout.
     */
    public ZooKeeperUpdatingListenerBuilder sessionTimeout(int timeout) {
        checkArgument(timeout > 0, "timeout: %s (expected: > 0)", timeout);
        this.sessionTimeout = timeout;
        return this;
    }

    /**
     * Sets the {@link Endpoint} to register.
     *
     * @param endpoint the {@link Endpoint} to register.
     */
    public ZooKeeperUpdatingListenerBuilder endpoint(Endpoint endpoint) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
        return this;
    }

    /**
     * Sets the {@link NodeValueCodec} to encode or decode zookeeper data.
     *
     * @param nodeValueCodec the {@link NodeValueCodec} instance to use.
     */
    public ZooKeeperUpdatingListenerBuilder nodeValueCodec(NodeValueCodec nodeValueCodec) {
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "requireNonNull");
        return this;
    }

    /**
     * Returns a newly-created {@link ServerListener} instance that registers the server to zookeeper
     * when the server starts.
     */
    public ZooKeeperUpdatingListener build() {
        if (client == null) {
            ExponentialBackoffRetry retryPolicy = new ExponentialBackoffRetry(DEFAULT_CONNECT_TIMEOUT, 3);
            client = CuratorFrameworkFactory.builder()
                                            .connectString(connect)
                                            .retryPolicy(retryPolicy)
                                            .connectionTimeoutMs(connectTimeout)
                                            .sessionTimeoutMs(sessionTimeout)
                                            .build();
        }

        return new ZooKeeperUpdatingListener(client, node, nodeValueCodec, endpoint);
    }
}

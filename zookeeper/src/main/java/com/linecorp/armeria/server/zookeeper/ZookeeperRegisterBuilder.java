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

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.server.ServerListener;

/**
 * Builds a new {@link ServerListener}, which registers the server to the zookeeper instance.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerListener register = new ZookeeperRegisterBuilder()
 * // Zookeeper Connection String
 * .connect("myZooKeeperHost:2181")
 * // Zookeeper Node to use
 * .node("/myProductionEndpoints")
 * // Session Timeout
 * .sessionTimeOut(10000)
 * // NodeValueCodec
 * .nodeValueCodec(NodeValueCodec.DEFAULT)
 * .build();
 * // Set to `Server`.
 * Server server = ...
 * server.serverListener(register);
 * }</pre>
 * */
public final class ZookeeperRegisterBuilder {
    private static final int DEFAULT_SESSION_TIMEOUT = 10000;

    private String connect;
    private String node;
    private int sessionTimeout = DEFAULT_SESSION_TIMEOUT;
    private Endpoint endpoint;
    private NodeValueCodec nodeValueCodec = NodeValueCodec.DEFAULT;

    /**
     * Sets the zookeeper connection string.
     *
     * @param connect the zookeeper connection string.
     */
    public ZookeeperRegisterBuilder connect(String connect) {
        checkArgument(!isNullOrEmpty(connect), "connect");
        this.connect = connect;
        return this;
    }

    /**
     * Sets the zookeeper node to register.
     *
     * @param node the zookeeper node to register.
     */
    public ZookeeperRegisterBuilder node(String node) {
        checkArgument(!isNullOrEmpty(node), "node");
        this.node = node;
        return this;
    }

    /**
     * Sets the session timeout (in ms). (default: 10000)
     *
     * @param timeout the session timeout.
     */
    public ZookeeperRegisterBuilder sessionTimeout(int timeout) {
        checkArgument(timeout > 0, "timeout: %s (expected: > 0)", timeout);
        this.sessionTimeout = timeout;
        return this;
    }

    /**
     * Sets the {@link Endpoint} to register.
     *
     * @param endpoint the {@link Endpoint} to register.
     */
    public ZookeeperRegisterBuilder endpoint(Endpoint endpoint) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
        return this;
    }

    /**
     * Sets the {@link NodeValueCodec} to encode or decode zookeeper data.
     *
     * @param nodeValueCodec the {@link NodeValueCodec} instance to use.
     */
    public ZookeeperRegisterBuilder nodeValueCodec(NodeValueCodec nodeValueCodec) {
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "requireNonNull");
        return this;
    }

    /**
     * Returns a newly-created {@link ServerListener} instance that registers the server to zookeeper
     * when the server starts.
     */
    public ServerListener build() {
        checkArgument(!isNullOrEmpty(connect), "connect");
        checkArgument(!isNullOrEmpty(node), "node");

        return new ZooKeeperUpdatingListener(connect, node, sessionTimeout, endpoint, nodeValueCodec);
    }
}

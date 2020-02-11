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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import javax.annotation.Nullable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;

import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.internal.common.zookeeper.ZooKeeperDefaults;

/**
 * Builds a new {@link ZooKeeperUpdatingListener}, which registers the server to a ZooKeeper cluster.
 * <h2>Examples</h2>
 * <pre>{@code
 * ZooKeeperUpdatingListener listener =
 *     ZooKeeperUpdatingListener.builder("myZooKeeperHost:2181", "/myProductionEndpoints")
 *                              .sessionTimeoutMillis(10000)
 *                              .nodeValueCodec(NodeValueCodec.ofDefault())
 *                              .build();
 * ServerBuilder sb = Server.builder();
 * sb.addListener(listener);
 * }</pre>
 *
 * <p>You can also specify the {@link CuratorFramework} instance to use. In this case,
 * invoking {@link #connectTimeout(Duration)}, {@link #connectTimeoutMillis(long)},
 * {@link #sessionTimeout(Duration)} or {@link #sessionTimeoutMillis(long)} will raise an
 * {@link IllegalStateException}.
 *
 * <pre>{@code
 * ZooKeeperUpdatingListener listener =
 *     ZooKeeperUpdatingListener.builder(curatorFramework, "/myProductionEndpoints")
 *                              .nodeValueCodec(NodeValueCodec.DEFAULT)
 *                              .build();
 * ServerBuilder sb = Server.builder();
 * sb.addListener(listener);
 * }</pre>
 * */
public final class ZooKeeperUpdatingListenerBuilder {
    @Nullable
    private CuratorFramework client;
    @Nullable
    private final String connectionStr;
    private final String zNodePath;
    private int connectTimeoutMillis = ZooKeeperDefaults.DEFAULT_CONNECT_TIMEOUT_MS;
    private int sessionTimeoutMillis = ZooKeeperDefaults.DEFAULT_SESSION_TIMEOUT_MS;
    @Nullable
    private Endpoint endpoint;
    private NodeValueCodec nodeValueCodec = NodeValueCodec.ofDefault();

    /**
     * Creates a {@link ZooKeeperUpdatingListenerBuilder} with a {@link CuratorFramework} instance and a zNode
     * path.
     *
     * @param client the curator framework instance
     * @param zNodePath the ZooKeeper node to register
     *
     * @deprecated Use {@link ZooKeeperUpdatingListener#builder(CuratorFramework, String)}.
     */
    @Deprecated
    public ZooKeeperUpdatingListenerBuilder(CuratorFramework client, String zNodePath) {
        this.client = requireNonNull(client, "client");
        connectionStr = null;
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        checkArgument(!this.zNodePath.isEmpty(), "zNodePath can't be empty");
    }

    /**
     * Creates a {@link ZooKeeperUpdatingListenerBuilder} with a ZooKeeper connection string and a zNode path.
     *
     * @param connectionStr the ZooKeeper connection string
     * @param zNodePath the ZooKeeper node to register
     *
     * @deprecated Use {@link ZooKeeperUpdatingListener#builder(String, String)}.
     */
    @Deprecated
    public ZooKeeperUpdatingListenerBuilder(String connectionStr, String zNodePath) {
        this.connectionStr = requireNonNull(connectionStr, "connectionStr");
        checkArgument(!this.connectionStr.isEmpty(), "connectionStr can't be empty");
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        checkArgument(!this.zNodePath.isEmpty(), "zNodePath can't be empty");
    }

    private void ensureInternalClient() {
        checkState(client == null,
                   "This method is allowed only when created with a connection string.");
    }

    /**
     * Sets the connect timeout.
     *
     * @param connectTimeout the connect timeout
     *
     * @throws IllegalStateException if this builder is constructed with
     *                               {@link ZooKeeperUpdatingListener#builder(CuratorFramework, String)}
     */
    public ZooKeeperUpdatingListenerBuilder connectTimeout(Duration connectTimeout) {
        requireNonNull(connectTimeout, "connectTimeout");
        checkArgument(!connectTimeout.isZero() && !connectTimeout.isNegative(),
                      "connectTimeout: %s (expected: > 0)", connectTimeout);
        return connectTimeoutMillis(connectTimeout.toMillis());
    }

    /**
     * Sets the connect timeout (in ms). (default: {@value ZooKeeperDefaults#DEFAULT_CONNECT_TIMEOUT_MS})
     *
     * @param connectTimeoutMillis the connect timeout
     *
     * @throws IllegalStateException if this builder is constructed with
     *                               {@link ZooKeeperUpdatingListener#builder(CuratorFramework, String)}
     */
    public ZooKeeperUpdatingListenerBuilder connectTimeoutMillis(long connectTimeoutMillis) {
        ensureInternalClient();
        checkArgument(connectTimeoutMillis > 0,
                      "connectTimeoutMillis: %s (expected: > 0)", connectTimeoutMillis);
        this.connectTimeoutMillis = Ints.saturatedCast(connectTimeoutMillis);
        return this;
    }

    /**
     * Sets the session timeout.
     *
     * @param sessionTimeout the session timeout
     *
     * @throws IllegalStateException if this builder is constructed with
     *                               {@link ZooKeeperUpdatingListener#builder(CuratorFramework, String)}
     */
    public ZooKeeperUpdatingListenerBuilder sessionTimeout(Duration sessionTimeout) {
        requireNonNull(sessionTimeout, "sessionTimeout");
        checkArgument(!sessionTimeout.isZero() && !sessionTimeout.isNegative(),
                      "sessionTimeout: %s (expected: > 0)", sessionTimeout);
        return sessionTimeoutMillis(sessionTimeout.toMillis());
    }

    /**
     * Sets the session timeout (in ms). (default: {@value ZooKeeperDefaults#DEFAULT_SESSION_TIMEOUT_MS})
     *
     * @param sessionTimeoutMillis the session timeout
     *
     * @throws IllegalStateException if this builder is constructed with
     *                               {@link ZooKeeperUpdatingListener#builder(CuratorFramework, String)}
     */
    public ZooKeeperUpdatingListenerBuilder sessionTimeoutMillis(long sessionTimeoutMillis) {
        ensureInternalClient();
        checkArgument(sessionTimeoutMillis > 0,
                      "sessionTimeoutMillis: %s (expected: > 0)", sessionTimeoutMillis);
        this.sessionTimeoutMillis = Ints.saturatedCast(sessionTimeoutMillis);
        return this;
    }

    /**
     * Sets the {@link Endpoint} to register. If not set, the current host name is used automatically.
     *
     * @param endpoint the {@link Endpoint} to register
     */
    public ZooKeeperUpdatingListenerBuilder endpoint(Endpoint endpoint) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
        return this;
    }

    /**
     * Sets the {@link NodeValueCodec} to encode or decode ZooKeeper data.
     *
     * @param nodeValueCodec the {@link NodeValueCodec} instance to use
     */
    public ZooKeeperUpdatingListenerBuilder nodeValueCodec(NodeValueCodec nodeValueCodec) {
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "nodeValueCodec");
        return this;
    }

    /**
     * Returns a newly-created {@link ZooKeeperUpdatingListener} instance that registers the server to
     * ZooKeeper when the server starts.
     */
    public ZooKeeperUpdatingListener build() {
        final boolean internalClient;
        if (client == null) {
            client = CuratorFrameworkFactory.builder()
                                            .connectString(connectionStr)
                                            .retryPolicy(ZooKeeperDefaults.DEFAULT_RETRY_POLICY)
                                            .connectionTimeoutMs(connectTimeoutMillis)
                                            .sessionTimeoutMs(sessionTimeoutMillis)
                                            .build();
            internalClient = true;
        } else {
            internalClient = false;
        }

        return new ZooKeeperUpdatingListener(client, zNodePath, nodeValueCodec, endpoint, internalClient);
    }
}

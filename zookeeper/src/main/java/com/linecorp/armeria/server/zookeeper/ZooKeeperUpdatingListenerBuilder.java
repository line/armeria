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
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory.Builder;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.AbstractCuratorFrameworkBuilder;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;

/**
 * Builds a new {@link ZooKeeperUpdatingListener}, which registers the server to a ZooKeeper cluster.
 * <h2>Examples</h2>
 * <pre>{@code
 * ZooKeeperUpdatingListener listener =
 *     ZooKeeperUpdatingListener.builder("myZooKeeperHost:2181", "/myProductionEndpoints")
 *                              .sessionTimeoutMillis(10000)
 *                              .codec(NodeValueCodec.ofDefault())
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
 *                              .codec(NodeValueCodec.ofDefault())
 *                              .build();
 * ServerBuilder sb = Server.builder();
 * sb.addListener(listener);
 * }</pre>
 * */
public final class ZooKeeperUpdatingListenerBuilder extends AbstractCuratorFrameworkBuilder {
    private final String zNodePath;
    @Nullable
    private Endpoint endpoint;
    private NodeValueCodec nodeValueCodec = NodeValueCodec.ofDefault();

    /**
     * Creates a {@link ZooKeeperUpdatingListenerBuilder} with a {@link CuratorFramework} instance and a zNode
     * path.
     *
     * @param client the curator framework instance
     * @param zNodePath the ZooKeeper node to register
     */
    ZooKeeperUpdatingListenerBuilder(CuratorFramework client, String zNodePath) {
        super(client);
        this.zNodePath = zNodePath;
        checkArgument(!this.zNodePath.isEmpty(), "zNodePath can't be empty.");
    }

    /**
     * Creates a {@link ZooKeeperUpdatingListenerBuilder} with a ZooKeeper connection string and a zNode path.
     *
     * @param zkConnectionStr the ZooKeeper connection string
     * @param zNodePath the ZooKeeper node to register
     */
    ZooKeeperUpdatingListenerBuilder(String zkConnectionStr, String zNodePath) {
        super(zkConnectionStr);
        this.zNodePath = zNodePath;
        checkArgument(!this.zNodePath.isEmpty(), "zNodePath can't be empty.");
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
    public ZooKeeperUpdatingListenerBuilder codec(NodeValueCodec nodeValueCodec) {
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "nodeValueCodec");
        return this;
    }

    /**
     * Sets the {@link NodeValueCodec} to encode or decode ZooKeeper data.
     *
     * @param nodeValueCodec the {@link NodeValueCodec} instance to use
     *
     * @deprecated Use {@link #codec(NodeValueCodec)}
     */
    @Deprecated
    public ZooKeeperUpdatingListenerBuilder nodeValueCodec(NodeValueCodec nodeValueCodec) {
        return codec(nodeValueCodec);
    }

    /**
     * Returns a newly-created {@link ZooKeeperUpdatingListener} instance that registers the server to
     * ZooKeeper when the server starts.
     */
    public ZooKeeperUpdatingListener build() {
        final CuratorFramework client = buildCuratorFramework();
        final boolean internalClient = !isUserSpecifiedCuratorFramework();

        return new ZooKeeperUpdatingListener(client, zNodePath, nodeValueCodec, endpoint, internalClient);
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public ZooKeeperUpdatingListenerBuilder connectTimeout(Duration connectTimeout) {
        return (ZooKeeperUpdatingListenerBuilder) super.connectTimeout(connectTimeout);
    }

    @Override
    public ZooKeeperUpdatingListenerBuilder connectTimeoutMillis(long connectTimeoutMillis) {
        return (ZooKeeperUpdatingListenerBuilder) super.connectTimeoutMillis(connectTimeoutMillis);
    }

    @Override
    public ZooKeeperUpdatingListenerBuilder sessionTimeout(Duration sessionTimeout) {
        return (ZooKeeperUpdatingListenerBuilder) super.sessionTimeout(sessionTimeout);
    }

    @Override
    public ZooKeeperUpdatingListenerBuilder sessionTimeoutMillis(long sessionTimeoutMillis) {
        return (ZooKeeperUpdatingListenerBuilder) super.sessionTimeoutMillis(sessionTimeoutMillis);
    }

    @Override
    public ZooKeeperUpdatingListenerBuilder customizer(Consumer<? super Builder> customizer) {
        return (ZooKeeperUpdatingListenerBuilder) super.customizer(customizer);
    }
}

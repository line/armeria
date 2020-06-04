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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Consumer;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory.Builder;

import com.linecorp.armeria.common.zookeeper.AbstractCuratorFrameworkBuilder;
import com.linecorp.armeria.server.Server;

/**
 * Builds a new {@link ZooKeeperUpdatingListener}, which registers the server to a ZooKeeper cluster.
 * <pre>{@code
 * ZookeeperRegistrationSpec spec = ZookeeperRegistrationSpec.curator("myServices");
 * ZooKeeperUpdatingListener listener =
 *     ZooKeeperUpdatingListener.builder("myZooKeeperHost:2181", "/myProductionEndpoints", spec)
 *                              .sessionTimeoutMillis(10000)
 *                              .build();
 * ServerBuilder sb = Server.builder();
 * sb.addListener(listener);
 * }</pre>
 * This registers the {@link Server} using the format compatible with
 * <a href="https://curator.apache.org/curator-x-discovery/index.html">Curator Service Discovery</a>.
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

    private final ZookeeperRegistrationSpec spec;

    /**
     * Creates a {@link ZooKeeperUpdatingListenerBuilder} with a {@link CuratorFramework} instance and a zNode
     * path.
     *
     * @param client the curator framework instance
     * @param zNodePath the ZooKeeper node to register
     */
    ZooKeeperUpdatingListenerBuilder(CuratorFramework client, String zNodePath,
                                     ZookeeperRegistrationSpec spec) {
        super(client, zNodePath);
        this.spec = requireNonNull(spec, "spec");
    }

    /**
     * Creates a {@link ZooKeeperUpdatingListenerBuilder} with a ZooKeeper connection string and a zNode path.
     *
     * @param zkConnectionStr the ZooKeeper connection string
     * @param zNodePath the ZooKeeper node to register
     */
    ZooKeeperUpdatingListenerBuilder(String zkConnectionStr, String zNodePath,
                                     ZookeeperRegistrationSpec spec) {
        super(zkConnectionStr, zNodePath);
        this.spec = requireNonNull(spec, "spec");
    }

    /**
     * Returns a newly-created {@link ZooKeeperUpdatingListener} instance that registers the server to
     * ZooKeeper when the server starts.
     */
    public ZooKeeperUpdatingListener build() {
        final CuratorFramework client = buildCuratorFramework();
        final boolean internalClient = !isUserSpecifiedCuratorFramework();

        return new ZooKeeperUpdatingListener(client, zNodePath(), spec, internalClient);
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

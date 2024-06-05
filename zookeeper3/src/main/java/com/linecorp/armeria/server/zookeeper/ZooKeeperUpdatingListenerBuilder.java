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

import org.apache.curator.framework.CuratorFramework;

import com.linecorp.armeria.common.zookeeper.AbstractCuratorFrameworkBuilder;
import com.linecorp.armeria.server.Server;

/**
 * Builds a new {@link ZooKeeperUpdatingListener}, which registers the server to a ZooKeeper cluster.
 * <pre>{@code
 * ZooKeeperRegistrationSpec spec = ZooKeeperRegistrationSpec.curator("myServices");
 * ZooKeeperUpdatingListener listener =
 *     ZooKeeperUpdatingListener.builder("myZooKeeperHost:2181", "/myProductionEndpoints", spec)
 *                              .sessionTimeoutMillis(10000)
 *                              .build();
 * ServerBuilder sb = Server.builder();
 * sb.serverListener(listener);
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
 * ZooKeeperRegistrationSpec spec = ...
 * ZooKeeperUpdatingListener listener =
 *     ZooKeeperUpdatingListener.builder(curatorFramework, "/myProductionEndpoints", spec)
 *                              .build();
 * ServerBuilder sb = Server.builder();
 * sb.serverListener(listener);
 * }</pre>
 * */
public final class ZooKeeperUpdatingListenerBuilder
        extends AbstractCuratorFrameworkBuilder<ZooKeeperUpdatingListenerBuilder> {

    private final ZooKeeperRegistrationSpec spec;

    /**
     * Creates a {@link ZooKeeperUpdatingListenerBuilder} with a {@link CuratorFramework} instance and a znode
     * path.
     *
     * @param client the curator framework instance
     * @param znodePath the ZooKeeper node to register
     */
    ZooKeeperUpdatingListenerBuilder(CuratorFramework client, String znodePath,
                                     ZooKeeperRegistrationSpec spec) {
        super(client, znodePath);
        this.spec = requireNonNull(spec, "spec");
    }

    /**
     * Creates a {@link ZooKeeperUpdatingListenerBuilder} with a ZooKeeper connection string and a znode path.
     *
     * @param zkConnectionStr the ZooKeeper connection string
     * @param znodePath the ZooKeeper node to register
     */
    ZooKeeperUpdatingListenerBuilder(String zkConnectionStr, String znodePath,
                                     ZooKeeperRegistrationSpec spec) {
        super(zkConnectionStr, znodePath);
        this.spec = requireNonNull(spec, "spec");
    }

    /**
     * Returns a newly-created {@link ZooKeeperUpdatingListener} instance that registers the server to
     * ZooKeeper when the server starts.
     */
    public ZooKeeperUpdatingListener build() {
        final CuratorFramework client = buildCuratorFramework();
        final boolean internalClient = !isUserSpecifiedCuratorFramework();

        return new ZooKeeperUpdatingListener(client, znodePath(), spec, internalClient);
    }
}

/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.zookeeper;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory.Builder;

import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.zookeeper.AbstractCuratorFrameworkBuilder;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;

/**
 * Builds a {@link ZooKeeperEndpointGroup}.
 */
public final class ZooKeeperEndpointGroupBuilder extends AbstractCuratorFrameworkBuilder {

    @Nullable
    private final CuratorFramework client;
    private final String zNodePath;

    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();
    private NodeValueCodec nodeValueCodec = NodeValueCodec.ofDefault();

    ZooKeeperEndpointGroupBuilder(String zkConnectionStr, String zNodePath) {
        super(zkConnectionStr);
        client = null;
        this.zNodePath = zNodePath;
    }

    ZooKeeperEndpointGroupBuilder(CuratorFramework client, String zNodePath) {
        this.client = client;
        this.zNodePath = zNodePath;
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link ZooKeeperEndpointGroup}.
     */
    public ZooKeeperEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    /**
     * Sets the {@link NodeValueCodec} of the {@link ZooKeeperEndpointGroup}.
     */
    public ZooKeeperEndpointGroupBuilder codec(NodeValueCodec nodeValueCodec) {
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "nodeValueCodec");
        return this;
    }

    /**
     * Returns a new {@link ZooKeeperEndpointGroup} created with the properties set so far.
     */
    public ZooKeeperEndpointGroup build() {
        final CuratorFramework zkClient;
        final boolean internalClient;
        if (client == null) {
            zkClient = buildCuratorFramework();
            internalClient = true;
        } else {
            zkClient = client;
            internalClient = false;
        }

        return new ZooKeeperEndpointGroup(selectionStrategy, zkClient, zNodePath,
                                          nodeValueCodec, internalClient);
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public ZooKeeperEndpointGroupBuilder connectTimeout(Duration connectTimeout) {
        return (ZooKeeperEndpointGroupBuilder) super.connectTimeout(connectTimeout);
    }

    @Override
    public ZooKeeperEndpointGroupBuilder connectTimeoutMillis(long connectTimeoutMillis) {
        return (ZooKeeperEndpointGroupBuilder) super.connectTimeoutMillis(connectTimeoutMillis);
    }

    @Override
    public ZooKeeperEndpointGroupBuilder sessionTimeout(Duration sessionTimeout) {
        return (ZooKeeperEndpointGroupBuilder) super.sessionTimeout(sessionTimeout);
    }

    @Override
    public ZooKeeperEndpointGroupBuilder sessionTimeoutMillis(long sessionTimeoutMillis) {
        return (ZooKeeperEndpointGroupBuilder) super.sessionTimeoutMillis(sessionTimeoutMillis);
    }

    @Override
    public ZooKeeperEndpointGroupBuilder customizer(Consumer<? super Builder> customizer) {
        return (ZooKeeperEndpointGroupBuilder) super.customizer(customizer);
    }
}

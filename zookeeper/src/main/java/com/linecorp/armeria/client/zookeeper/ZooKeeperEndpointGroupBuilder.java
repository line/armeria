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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.zookeeper.ZooKeeperDefaults.DEFAULT_CONNECT_TIMEOUT_MS;
import static com.linecorp.armeria.internal.common.zookeeper.ZooKeeperDefaults.DEFAULT_RETRY_POLICY;
import static com.linecorp.armeria.internal.common.zookeeper.ZooKeeperDefaults.DEFAULT_SESSION_TIMEOUT_MS;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;

import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;

/**
 * Builds a {@link ZooKeeperEndpointGroup}.
 */
public final class ZooKeeperEndpointGroupBuilder {

    @Nullable
    private final CuratorFramework client;
    @Nullable
    private final CuratorFrameworkFactory.Builder clientBuilder;
    @Nullable
    private final List<Consumer<? super CuratorFrameworkFactory.Builder>> customizers;
    private final String zNodePath;

    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();
    private NodeValueCodec nodeValueCodec = NodeValueCodec.ofDefault();

    ZooKeeperEndpointGroupBuilder(String zkConnectionStr, String zNodePath) {
        clientBuilder = CuratorFrameworkFactory.builder()
                                               .connectString(zkConnectionStr)
                                               .connectionTimeoutMs(DEFAULT_CONNECT_TIMEOUT_MS)
                                               .sessionTimeoutMs(DEFAULT_SESSION_TIMEOUT_MS)
                                               .retryPolicy(DEFAULT_RETRY_POLICY);
        this.zNodePath = zNodePath;
        customizers = new ArrayList<>();

        client = null;
    }

    ZooKeeperEndpointGroupBuilder(CuratorFramework client, String zNodePath) {
        this.client = client;
        this.zNodePath = zNodePath;

        clientBuilder = null;
        customizers = null;
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
     * Specifies the {@link Consumer} that customizes the {@link CuratorFramework}.
     *
     * @throws IllegalStateException if this builder was created with an existing {@link CuratorFramework}
     *                               instance.
     */
    public ZooKeeperEndpointGroupBuilder customizer(
            Consumer<? super CuratorFrameworkFactory.Builder> customizer) {
        checkState(customizers != null, "Cannot customize if using an existing CuratorFramework instance.");
        customizers.add(requireNonNull(customizer, "customizer"));
        return this;
    }

    /**
     * Returns a new {@link ZooKeeperEndpointGroup} created with the properties set so far.
     */
    public ZooKeeperEndpointGroup build() {
        final CuratorFramework client;
        final boolean internalClient;
        if (clientBuilder != null) {
            assert customizers != null;
            customizers.forEach(c -> c.accept(clientBuilder));
            client = clientBuilder.build();
            internalClient = true;
        } else {
            assert this.client != null;
            client = this.client;
            internalClient = false;
        }

        return new ZooKeeperEndpointGroup(selectionStrategy, client, zNodePath, nodeValueCodec, internalClient);
    }
}

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

import org.apache.curator.framework.CuratorFramework;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.AbstractDynamicEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroupSetters;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.zookeeper.AbstractCuratorFrameworkBuilder;

/**
 * Builds a {@link ZooKeeperEndpointGroup}.
 */
public final class ZooKeeperEndpointGroupBuilder
        extends AbstractCuratorFrameworkBuilder<ZooKeeperEndpointGroupBuilder>
        implements DynamicEndpointGroupSetters<ZooKeeperEndpointGroupBuilder> {

    private final DynamicEndpointGroupBuilder dynamicEndpointGroupBuilder = new DynamicEndpointGroupBuilder();
    private final ZooKeeperDiscoverySpec spec;
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    ZooKeeperEndpointGroupBuilder(String zkConnectionStr, String znodePath, ZooKeeperDiscoverySpec spec) {
        super(zkConnectionStr, znodePath);
        this.spec = requireNonNull(spec, "spec");
    }

    ZooKeeperEndpointGroupBuilder(CuratorFramework client, String znodePath, ZooKeeperDiscoverySpec spec) {
        super(client, znodePath);
        this.spec = requireNonNull(spec, "spec");
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link ZooKeeperEndpointGroup}.
     */
    public ZooKeeperEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    /**
     * Returns a newly-created {@link ZooKeeperEndpointGroup} based on the properties set so far.
     */
    public ZooKeeperEndpointGroup build() {
        final CuratorFramework client = buildCuratorFramework();
        final boolean internalClient = !isUserSpecifiedCuratorFramework();
        final boolean allowEmptyEndpoints = dynamicEndpointGroupBuilder.shouldAllowEmptyEndpoints();
        final long selectionTimeoutMillis = dynamicEndpointGroupBuilder.selectionTimeoutMillis();

        return new ZooKeeperEndpointGroup(selectionStrategy, allowEmptyEndpoints, selectionTimeoutMillis,
                                          client, znodePath(), spec, internalClient);
    }

    @Override
    public ZooKeeperEndpointGroupBuilder allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        dynamicEndpointGroupBuilder.allowEmptyEndpoints(allowEmptyEndpoints);
        return this;
    }

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * {@link Duration#ZERO} disables the timeout.
     * If unspecified, {@link Flags#defaultResponseTimeoutMillis()} is used by default.
     */
    @Override
    public ZooKeeperEndpointGroupBuilder selectionTimeout(Duration selectionTimeout) {
        dynamicEndpointGroupBuilder.selectionTimeout(selectionTimeout);
        return this;
    }

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * {@code 0} disables the timeout.
     * If unspecified, {@link Flags#defaultResponseTimeoutMillis()} is used by default.
     */
    @Override
    public ZooKeeperEndpointGroupBuilder selectionTimeoutMillis(long selectionTimeoutMillis) {
        dynamicEndpointGroupBuilder.selectionTimeoutMillis(selectionTimeoutMillis);
        return this;
    }

    /**
     * This workaround delegates DynamicEndpointGroupSetters properties to AbstractDynamicEndpointGroupBuilder.
     * ZooKeeperEndpointGroupBuilder can't extend AbstractDynamicEndpointGroupBuilder because it already extends
     * AbstractCuratorFrameworkBuilder.
     */
    private static class DynamicEndpointGroupBuilder
            extends AbstractDynamicEndpointGroupBuilder<DynamicEndpointGroupBuilder> {
        protected DynamicEndpointGroupBuilder() {
            super(Flags.defaultResponseTimeoutMillis());
        }

        @Override
        public boolean shouldAllowEmptyEndpoints() {
            return super.shouldAllowEmptyEndpoints();
        }

        @Override
        public long selectionTimeoutMillis() {
            return super.selectionTimeoutMillis();
        }
    }
}

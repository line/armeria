/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.netty.util.concurrent.EventExecutor;

/**
 * A load balancer which allows users to select an {@link Endpoint} based on a {@link ClientRequestContext}.
 * A {@link XdsLoadBalancer} is bound to a {@link ClusterSnapshot} and can be accessed via
 * {@link ClusterSnapshot#loadBalancer()}.
 */
@UnstableApi
public interface XdsLoadBalancer extends EndpointSelector {

    /**
     * Creates a {@link UpdatableXdsLoadBalancer} based on the input parameters.
     * Rather than instantiating a {@link XdsLoadBalancer} directly, users are encouraged
     * to use the load balancer provided by {@link ClusterSnapshot#loadBalancer()} to select
     * {@link Endpoint}s.
     */
    static UpdatableXdsLoadBalancer of(EventExecutor eventLoop, Locality locality,
                                       @Nullable XdsLoadBalancer localLoadBalancer) {
        return new DefaultXdsLoadBalancer(eventLoop, locality, localLoadBalancer);
    }

    @Override
    CompletableFuture<Endpoint> select(ClientRequestContext ctx, ScheduledExecutorService executor,
                                       long timeoutMillis);

    /**
     * Adds a listener which is notified of the list of endpoints when there is a change.
     *
     * @deprecated do not use.
     */
    @Deprecated
    void addEndpointsListener(Consumer<? super List<Endpoint>> listener);

    /**
     * Removes a listener.
     *
     * @deprecated do not use.
     */
    @Deprecated
    void removeEndpointsListener(Consumer<? super List<Endpoint>> listener);
}

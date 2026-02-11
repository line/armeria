/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import javax.annotation.concurrent.NotThreadSafe;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ClusterXdsResource;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link XdsLoadBalancer} factory.
 * A factory maintains a state which can be propagated across different
 * {@link ClusterLoadAssignment}s with the same identifier.
 * Users are encouraged to use {@link XdsBootstrap} to retrieve an instance of {@link XdsLoadBalancer}
 * instead of using this class directly.
 */
@UnstableApi
@NotThreadSafe
public interface XdsLoadBalancerFactory extends SafeCloseable {

    /**
     * Creates a {@link XdsLoadBalancerFactory} based on the input parameters.
     * Rather than instantiating a {@link XdsLoadBalancer} directly, users are encouraged
     * to use the load balancer provided by {@link ClusterSnapshot#loadBalancer()} to select
     * {@link Endpoint}s.
     */
    static XdsLoadBalancerFactory of(EventExecutor eventLoop, Locality locality,
                                     XdsLoadBalancerLifecycleObserver lifecycleObserver) {
        return new DefaultXdsLoadBalancerFactory(eventLoop, locality, lifecycleObserver);
    }

    /**
     * Registers a {@link SnapshotWatcher} to watch for {@link XdsLoadBalancer}s created using
     * the supplied cluster and endpoint information.
     * A {@link XdsLoadBalancer} is immutable, and a new {@link XdsLoadBalancer} will be generated
     * when endpoints change. (e.g. if an endpoint becomes unhealthy, a new {@link XdsLoadBalancer}
     * will be passed to the {@link SnapshotWatcher}).
     * Note that there are no thread-safety guarantees with this method.
     */
    void register(ClusterXdsResource clusterXdsResource, EndpointSnapshot endpointSnapshot,
                  SnapshotWatcher<XdsLoadBalancer> watcher,
                  @Nullable XdsLoadBalancer localLoadBalancer);
}

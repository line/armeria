/*
 * Copyright 2026 LY Corporation
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

import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.ClusterXdsResource;
import com.linecorp.armeria.xds.TransportSocketMatchSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.SnapshotStream;

/**
 * Creates {@link SnapshotStream}s of {@link XdsLoadBalancer} from cluster and endpoint data.
 *
 * <p>Instances are scoped to a single cluster and may preserve per-endpoint state
 * (e.g., creation timestamps for slow-start ramping) across stream switches.
 */
@UnstableApi
public interface XdsLoadBalancerFactory extends SafeCloseable {

    /**
     * Creates a new {@link XdsLoadBalancerFactory}.
     *
     * @param context the factory context providing bootstrap, event loop, and metrics
     * @param clusterName the cluster name for metric tags
     */
    static XdsLoadBalancerFactory of(FactoryContext context, String clusterName) {
        return new DefaultXdsLoadBalancerFactory(context, clusterName);
    }

    /**
     * Creates a {@link SnapshotStream} that emits a new {@link XdsLoadBalancer} whenever
     * the underlying endpoints change.
     *
     * @param clusterXdsResource the cluster resource
     * @param transportSocket the transport socket snapshot
     * @param transportSocketMatches the transport socket match snapshots
     * @param localLoadBalancer the local cluster load balancer for zone-aware routing, or {@code null}
     */
    SnapshotStream<XdsLoadBalancer> register(ClusterXdsResource clusterXdsResource,
                                             TransportSocketSnapshot transportSocket,
                                             List<TransportSocketMatchSnapshot> transportSocketMatches,
                                             @Nullable XdsLoadBalancer localLoadBalancer);
}

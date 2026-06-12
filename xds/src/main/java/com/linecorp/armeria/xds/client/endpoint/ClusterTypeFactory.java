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

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.ClusterXdsResource;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.XdsExtensionFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A factory that creates a {@link SnapshotStream} of endpoints for a given cluster.
 *
 * <p>Implementations are discovered via the Java {@link java.util.ServiceLoader} mechanism
 * and registered in the extension registry by type URL or name. For built-in cluster types
 * (STATIC, EDS, STRICT_DNS), the default implementation is used. Custom cluster types
 * ({@code CustomClusterType}) can provide their own factory to handle endpoint resolution.
 *
 * <p>Transport socket matching and health check wrapping are applied by the framework after
 * subscribing to the returned stream, so implementations do not need to handle them.
 */
@UnstableApi
public interface ClusterTypeFactory extends XdsExtensionFactory {

    /**
     * Creates a {@link SnapshotStream} that emits {@link EndpointSnapshot}s for the given cluster.
     *
     * <p>Custom cluster types may perform their own endpoint discovery
     * and return snapshots via {@link EndpointSnapshot#of(ClusterLoadAssignment)}.
     *
     * <p>The framework converts the returned {@link EndpointSnapshot} to endpoints and
     * applies transport socket matching, health check wrapping, and load balancer construction.
     *
     * @param clusterXdsResource the cluster resource describing the cluster configuration
     * @param context the {@link FactoryContext} providing runtime infrastructure
     * @return a stream that emits {@link EndpointSnapshot}s
     */
    SnapshotStream<EndpointSnapshot> createEndpointStream(
            ClusterXdsResource clusterXdsResource, FactoryContext context);
}

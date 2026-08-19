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

package com.linecorp.armeria.xds.kubernetes;

import java.util.List;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * Maps a list of Armeria {@link Endpoint}s from a
 * {@link com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroup KubernetesEndpointGroup}
 * into a {@link ClusterLoadAssignment} protobuf for xDS endpoint resolution.
 *
 * <p>Users who need locality-aware load balancing, priority tiers, or custom health mapping
 * can provide their own implementation via
 * {@link KubernetesClusterTypeFactory#of(KubernetesEndpointMapper)}.
 *
 * <p>The default mapping places all endpoints in a single
 * {@link io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints LocalityLbEndpoints}
 * with equal weight, priority 0, and HEALTHY status.
 */
@UnstableApi
@FunctionalInterface
public interface KubernetesEndpointMapper {

    /**
     * Returns a default {@link KubernetesEndpointMapper} that places all endpoints in a single
     * {@link io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints LocalityLbEndpoints}
     * with equal weight, priority 0, and
     * {@link io.envoyproxy.envoy.config.core.v3.HealthStatus#HEALTHY HEALTHY} status.
     */
    static KubernetesEndpointMapper of() {
        return DefaultKubernetesEndpointMapper.INSTANCE;
    }

    /**
     * Converts the given list of {@link Endpoint}s into a {@link ClusterLoadAssignment}.
     *
     * @param clusterName the xDS cluster name
     * @param endpoints the endpoints discovered from Kubernetes
     * @return the constructed {@link ClusterLoadAssignment}
     */
    ClusterLoadAssignment map(String clusterName, List<Endpoint> endpoints);
}

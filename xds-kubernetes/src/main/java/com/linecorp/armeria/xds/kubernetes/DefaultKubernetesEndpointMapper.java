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
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;

import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

enum DefaultKubernetesEndpointMapper implements KubernetesEndpointMapper {

    INSTANCE;

    @Override
    public ClusterLoadAssignment map(String clusterName, List<Endpoint> endpoints) {
        final LocalityLbEndpoints.Builder localityBuilder = LocalityLbEndpoints.newBuilder();
        final Set<Endpoint> deduped = ImmutableSet.copyOf(endpoints);
        for (Endpoint endpoint : deduped) {
            final SocketAddress.Builder sa = SocketAddress.newBuilder()
                                                          .setAddress(endpoint.host());
            if (endpoint.hasPort()) {
                sa.setPortValue(endpoint.port());
            }
            localityBuilder.addLbEndpoints(
                    LbEndpoint.newBuilder()
                              .setHealthStatus(HealthStatus.HEALTHY)
                              .setEndpoint(
                                      io.envoyproxy.envoy.config.endpoint.v3.Endpoint.newBuilder()
                                              .setAddress(Address.newBuilder()
                                                                 .setSocketAddress(sa))));
        }

        return ClusterLoadAssignment.newBuilder()
                                    .setClusterName(clusterName)
                                    .addEndpoints(localityBuilder)
                                    .build();
    }
}

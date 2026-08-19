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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

class DefaultKubernetesEndpointMapperTest {

    @Test
    void mapsEndpointsToSingleLocality() {
        final List<Endpoint> endpoints = ImmutableList.of(
                Endpoint.of("10.0.0.1", 8080),
                Endpoint.of("10.0.0.2", 8080),
                Endpoint.of("10.0.0.3", 9090));

        final ClusterLoadAssignment cla =
                KubernetesEndpointMapper.of().map("test-cluster", endpoints);

        assertThat(cla.getClusterName()).isEqualTo("test-cluster");
        assertThat(cla.getEndpointsList()).hasSize(1);

        final LocalityLbEndpoints locality = cla.getEndpoints(0);
        assertThat(locality.getLbEndpointsList()).hasSize(3);

        final LbEndpoint ep0 = locality.getLbEndpoints(0);
        assertThat(ep0.getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(ep0.getEndpoint().getAddress().getSocketAddress().getAddress())
                .isEqualTo("10.0.0.1");
        assertThat(ep0.getEndpoint().getAddress().getSocketAddress().getPortValue())
                .isEqualTo(8080);

        final LbEndpoint ep2 = locality.getLbEndpoints(2);
        assertThat(ep2.getEndpoint().getAddress().getSocketAddress().getAddress())
                .isEqualTo("10.0.0.3");
        assertThat(ep2.getEndpoint().getAddress().getSocketAddress().getPortValue())
                .isEqualTo(9090);
    }

    @Test
    void emptyEndpointsProducesEmptyLocality() {
        final ClusterLoadAssignment cla =
                KubernetesEndpointMapper.of().map("empty-cluster", ImmutableList.of());

        assertThat(cla.getClusterName()).isEqualTo("empty-cluster");
        assertThat(cla.getEndpointsList()).hasSize(1);
        assertThat(cla.getEndpoints(0).getLbEndpointsList()).isEmpty();
    }

    @Test
    void deduplicatesByHostPort() {
        // Simulates NODE_PORT mode where multiple pods on the same node produce
        // duplicate endpoints with the same nodeIP:nodePort.
        final List<Endpoint> endpoints = ImmutableList.of(
                Endpoint.of("192.168.1.1", 30000),
                Endpoint.of("192.168.1.1", 30000),
                Endpoint.of("192.168.1.2", 30000));

        final ClusterLoadAssignment cla =
                KubernetesEndpointMapper.of().map("dedup-cluster", endpoints);

        final LocalityLbEndpoints locality = cla.getEndpoints(0);
        assertThat(locality.getLbEndpointsList()).hasSize(2);
        assertThat(locality.getLbEndpoints(0).getEndpoint().getAddress()
                           .getSocketAddress().getAddress()).isEqualTo("192.168.1.1");
        assertThat(locality.getLbEndpoints(1).getEndpoint().getAddress()
                           .getSocketAddress().getAddress()).isEqualTo("192.168.1.2");
    }

    @Test
    void endpointWithoutPortOmitsPortValue() {
        final List<Endpoint> endpoints = ImmutableList.of(Endpoint.of("10.0.0.1"));

        final ClusterLoadAssignment cla =
                KubernetesEndpointMapper.of().map("no-port-cluster", endpoints);

        final LbEndpoint ep = cla.getEndpoints(0).getLbEndpoints(0);
        assertThat(ep.getEndpoint().getAddress().getSocketAddress().getAddress())
                .isEqualTo("10.0.0.1");
        assertThat(ep.getEndpoint().getAddress().getSocketAddress().hasPortValue()).isFalse();
    }
}

/*
 * Copyright 2024 LINE Corporation
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

import static com.linecorp.armeria.xds.XdsTestResources.createStaticCluster;
import static com.linecorp.armeria.xds.XdsTestResources.endpoint;
import static com.linecorp.armeria.xds.XdsTestResources.locality;
import static com.linecorp.armeria.xds.XdsTestResources.localityLbEndpoints;
import static com.linecorp.armeria.xds.XdsTestResources.staticBootstrap;
import static com.linecorp.armeria.xds.XdsTestResources.staticResourceListener;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig.Builder;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig.LocalityWeightedLbConfig;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;

class LocalityTest {

    private static final Builder LOCALITY_LB_CONFIG =
            CommonLbConfig.newBuilder()
                          .setLocalityWeightedLbConfig(LocalityWeightedLbConfig.getDefaultInstance());

    @Test
    void basicCase() {
        final Listener listener = staticResourceListener();

        final List<LbEndpoint> lbEndpointsA =
                ImmutableList.of(endpoint("127.0.0.1", 8080, 1000));
        final List<LbEndpoint> lbEndpointsB =
                ImmutableList.of(endpoint("127.0.0.1", 8081, 1));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(locality("regionA"), lbEndpointsA, 0, 9))
                        .addEndpoints(localityLbEndpoints(locality("regionB"), lbEndpointsB, 0, 1))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(LOCALITY_LB_CONFIG).build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        final Map<Endpoint, Integer> countsMap = new HashMap<>();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            // Regardless of the endpoint weight, the locality weight will be used
            // to determine which endpoint group to use
            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            for (int i = 0; i < 10; i++) {
                final Endpoint selected = endpointGroup.selectNow(ctx);
                assertThat(selected).isNotNull();
                countsMap.compute(selected, (k, v) -> v == null ? 1 : v + 1);
            }
            assertThat(countsMap)
                    .containsExactlyInAnyOrderEntriesOf(
                            ImmutableMap.of(Endpoint.of("127.0.0.1", 8080).withWeight(1000), 9,
                                            Endpoint.of("127.0.0.1", 8081).withWeight(1), 1));
        }
    }

    @Test
    void emptyLocality() {
        final Listener listener = staticResourceListener();

        final List<LbEndpoint> lbEndpointsA = ImmutableList.of();
        final List<LbEndpoint> lbEndpointsB =
                ImmutableList.of(endpoint("127.0.0.1", 8081),
                                 endpoint("127.0.0.1", 8081));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(locality("regionA"), lbEndpointsA))
                                     .addEndpoints(localityLbEndpoints(locality("regionB"), lbEndpointsB))
                                     .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(LOCALITY_LB_CONFIG).build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            // regionA won't be selected at all since it is empty
            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
        }
    }

    @Test
    void multiPriorityAndLocality() {
        final Listener listener = staticResourceListener();

        final List<LbEndpoint> lbEndpointsA =
                ImmutableList.of(endpoint("127.0.0.1", 8080, HealthStatus.HEALTHY));
        // the unhealthy endpoint won't be selected due to priority selection
        final List<LbEndpoint> lbEndpointsB =
                ImmutableList.of(endpoint("127.0.0.1", 8081, HealthStatus.UNHEALTHY));
        final List<LbEndpoint> lbEndpointsC =
                ImmutableList.of(endpoint("127.0.0.1", 8082, HealthStatus.HEALTHY));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(locality("regionA"), lbEndpointsA, 0, 9))
                        .addEndpoints(localityLbEndpoints(locality("regionB"), lbEndpointsB, 0, 1000))
                        .addEndpoints(localityLbEndpoints(locality("regionC"), lbEndpointsC, 0, 1))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(LOCALITY_LB_CONFIG).build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());

            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final Map<Endpoint, Integer> countsMap = new HashMap<>();
            for (int i = 0; i < 10; i++) {
                final Endpoint selected = endpointGroup.selectNow(ctx);
                assertThat(selected).isNotNull();
                countsMap.compute(selected, (k, v) -> v == null ? 1 : v + 1);
            }
            assertThat(countsMap)
                    .containsExactlyInAnyOrderEntriesOf(
                            ImmutableMap.of(Endpoint.of("127.0.0.1", 8080), 9,
                                            Endpoint.of("127.0.0.1", 8082), 1));
        }
    }
}

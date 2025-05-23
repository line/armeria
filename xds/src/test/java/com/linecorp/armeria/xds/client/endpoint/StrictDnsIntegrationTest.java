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
import static com.linecorp.armeria.xds.XdsTestResources.localityLbEndpoints;
import static com.linecorp.armeria.xds.XdsTestResources.staticBootstrap;
import static com.linecorp.armeria.xds.XdsTestResources.staticResourceListener;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;

class StrictDnsIntegrationTest {

    @Test
    void basicCase() {
        final Listener listener = staticResourceListener();
        final int weight = 6;

        // use armeria.dev until we implement a better way to override the dns resolver via xDS
        final LbEndpoint lbEndpoint = endpoint("armeria.dev", 80, weight);
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoint))
                        .setPolicy(Policy.newBuilder()
                                         .setWeightedPriorityHealth(true))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .setType(DiscoveryType.STRICT_DNS)
                .build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor httpPreprocessor = XdsHttpPreprocessor.of("listener", xdsBootstrap)) {
            final DecoratingHttpClientFunction decorator =
                    (delegate, ctx, req) -> HttpResponse.of(String.valueOf(ctx.endpoint().weight()));
            final BlockingWebClient client = WebClient.builder(httpPreprocessor)
                                                      .decorator(decorator)
                                                      .build()
                                                      .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo(String.valueOf(weight));
        }
    }
}

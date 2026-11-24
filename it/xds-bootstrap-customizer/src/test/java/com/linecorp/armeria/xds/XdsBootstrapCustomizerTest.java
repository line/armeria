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

package com.linecorp.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;

class XdsBootstrapCustomizerTest {

    @Test
    void customizerAddsStaticCluster() {
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(Bootstrap.getDefaultInstance())) {
            assertThat(xdsBootstrap.bootstrap().getStaticResources().getClustersList())
                    .extracting(Cluster::getName)
                    .contains(TestBootstrapCustomizer.CLUSTER_NAME);
        }
    }

    @Test
    void customizerPreservesExistingResources() {
        final Cluster existingCluster = Cluster.newBuilder()
                .setName("user-cluster")
                .build();
        final Bootstrap original = Bootstrap.newBuilder()
                .setStaticResources(
                        Bootstrap.StaticResources.newBuilder()
                                .addClusters(existingCluster))
                .build();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(original)) {
            assertThat(xdsBootstrap.bootstrap().getStaticResources().getClustersList())
                    .extracting(Cluster::getName)
                    .contains("user-cluster", TestBootstrapCustomizer.CLUSTER_NAME);
        }
    }

    @Test
    void ignoreBootstrapCustomizers() {
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(Bootstrap.getDefaultInstance())
                                                     .ignoreBootstrapCustomizers()
                                                     .build()) {
            assertThat(xdsBootstrap.bootstrap().getStaticResources().getClustersList())
                    .extracting(Cluster::getName)
                    .doesNotContain(TestBootstrapCustomizer.CLUSTER_NAME);
        }
    }
}

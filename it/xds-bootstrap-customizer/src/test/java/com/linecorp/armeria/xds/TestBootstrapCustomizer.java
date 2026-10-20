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

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;

public final class TestBootstrapCustomizer implements XdsBootstrapCustomizer {

    static final String CLUSTER_NAME = "_test.customizer-cluster";

    private static final Cluster CLUSTER = XdsResourceReader.from("""
            name: _test.customizer-cluster
            type: STATIC
            load_assignment:
              cluster_name: _test.customizer-cluster
              endpoints:
                - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 8080
            """, Cluster.class);

    @Override
    public Bootstrap customize(Bootstrap bootstrap) {
        return bootstrap.toBuilder()
                        .setStaticResources(
                                bootstrap.getStaticResources().toBuilder()
                                         .addClusters(CLUSTER))
                        .build();
    }
}

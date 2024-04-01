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

import static com.linecorp.armeria.xds.XdsTestResources.endpoint;
import static com.linecorp.armeria.xds.XdsTestResources.stringValue;
import static com.linecorp.armeria.xds.client.endpoint.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;
import static com.linecorp.armeria.xds.client.endpoint.XdsEndpointUtil.convertEndpoints;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.protobuf.Struct;

import com.linecorp.armeria.client.Endpoint;

import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

class XdsConverterUtilTest {

    @Test
    void convertEndpointsWithFilterMetadata() {
        final ClusterLoadAssignment loadAssignment = sampleClusterLoadAssignment();
        final List<Endpoint> endpoints =
                convertEndpoints(loadAssignment, Struct.newBuilder()
                                                       .putFields("foo", stringValue("foo1"))
                                                       .putFields("bar", stringValue("bar1"))
                                                       .build());
        assertThat(endpoints).containsExactly(Endpoint.of("127.0.0.1", 8082));
    }

    static ClusterLoadAssignment sampleClusterLoadAssignment() {
        final Metadata metadata1 =
                Metadata.newBuilder()
                        .putFilterMetadata(SUBSET_LOAD_BALANCING_FILTER_NAME,
                                           Struct.newBuilder()
                                                 .putFields("foo", stringValue("foo1"))
                                                 .build())
                        .build();
        final LbEndpoint endpoint1 = endpoint("127.0.0.1", 8080, metadata1);
        final Metadata metadata2 =
                Metadata.newBuilder()
                        .putFilterMetadata(SUBSET_LOAD_BALANCING_FILTER_NAME,
                                           Struct.newBuilder()
                                                 .putFields("foo", stringValue("foo1"))
                                                 .putFields("bar", stringValue("bar2"))
                                                 .build())
                        .build();
        final LbEndpoint endpoint2 = endpoint("127.0.0.1", 8081, metadata2);
        final Metadata metadata3 =
                Metadata.newBuilder()
                        .putFilterMetadata(SUBSET_LOAD_BALANCING_FILTER_NAME,
                                           Struct.newBuilder()
                                                 .putFields("foo", stringValue("foo1"))
                                                 .putFields("bar", stringValue("bar1"))
                                                 .putFields("baz", stringValue("baz1"))
                                                 .build())
                        .build();
        final LbEndpoint endpoint3 = endpoint("127.0.0.1", 8082, metadata3);
        final LocalityLbEndpoints lbEndpoints =
                LocalityLbEndpoints.newBuilder()
                                   .addLbEndpoints(endpoint1)
                                   .addLbEndpoints(endpoint2)
                                   .addLbEndpoints(endpoint3)
                                   .build();
        return ClusterLoadAssignment.newBuilder()
                            .setClusterName("cluster")
                            .addEndpoints(lbEndpoints)
                            .build();
    }
}

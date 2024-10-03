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
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.Endpoint;

import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

class XdsConverterUtilTest {

    @Test
    void convertEndpointsWithFilterMetadata() {
        final Metadata metadata1 = metadata(ImmutableMap.of("foo", "foo1"));
        final LbEndpoint lbEndpoint1 = endpoint("127.0.0.1", 8080, metadata1);
        final Endpoint endpoint1 = Endpoint.of("127.0.0.1", 8080)
                                           .withAttr(XdsAttributeKeys.LB_ENDPOINT_KEY, lbEndpoint1);
        final Metadata metadata2 = metadata(ImmutableMap.of("foo", "foo1", "bar", "bar2"));
        final LbEndpoint lbEndpoint2 = endpoint("127.0.0.1", 8081, metadata2);
        final Endpoint endpoint2 = Endpoint.of("127.0.0.1", 8081)
                                           .withAttr(XdsAttributeKeys.LB_ENDPOINT_KEY, lbEndpoint2);
        final Metadata metadata3 = metadata(ImmutableMap.of("foo", "foo1", "bar", "bar1", "baz", "baz1"));
        final LbEndpoint lbEndpoint3 = endpoint("127.0.0.1", 8082, metadata3);
        final Endpoint endpoint3 = Endpoint.of("127.0.0.1", 8082)
                                           .withAttr(XdsAttributeKeys.LB_ENDPOINT_KEY, lbEndpoint3);
        final List<Endpoint> endpoints =
                convertEndpoints(ImmutableList.of(endpoint1, endpoint2, endpoint3), Struct.newBuilder()
                                                      .putFields("foo", stringValue("foo1"))
                                                      .putFields("bar", stringValue("bar1"))
                                                      .build());
        assertThat(endpoints).containsExactly(Endpoint.of("127.0.0.1", 8082));
    }

    static Metadata metadata(Struct struct) {
        return Metadata.newBuilder().putFilterMetadata(SUBSET_LOAD_BALANCING_FILTER_NAME, struct)
                       .build();
    }

    static Metadata metadata(Map<String, String> map) {
        return metadata(struct(map));
    }

    static Struct struct(Map<String, String> map) {
        final Map<String, Value> structMap =
                map.entrySet().stream()
                   .collect(Collectors.toMap(Entry::getKey,
                                             e -> Value.newBuilder()
                                                       .setStringValue(e.getValue()).build()));
        return Struct.newBuilder().putAllFields(structMap).build();
    }

    static ClusterLoadAssignment sampleClusterLoadAssignment(String clusterName) {
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
                            .setClusterName(clusterName)
                            .addEndpoints(lbEndpoints)
                            .build();
    }
}

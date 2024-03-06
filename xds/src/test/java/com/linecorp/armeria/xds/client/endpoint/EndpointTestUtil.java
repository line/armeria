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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.google.protobuf.Any;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager.CodecType;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

final class EndpointTestUtil {

    static Listener staticResourceListener() {
        return staticResourceListener(Metadata.getDefaultInstance());
    }

    static Listener staticResourceListener(Metadata metadata) {
        final RouteAction.Builder routeActionBuilder = RouteAction.newBuilder().setCluster("cluster");
        if (metadata != Metadata.getDefaultInstance()) {
            routeActionBuilder.setMetadataMatch(metadata);
        }
        final VirtualHost virtualHost =
                VirtualHost.newBuilder()
                           .setName("route")
                           .addDomains("*")
                           .addRoutes(Route.newBuilder()
                                           .setMatch(RouteMatch.newBuilder().setPrefix("/"))
                                           .setRoute(routeActionBuilder))
                           .build();
        final HttpConnectionManager manager =
                HttpConnectionManager
                        .newBuilder()
                        .setCodecType(CodecType.AUTO)
                        .setStatPrefix("ingress_http")
                        .setRouteConfig(RouteConfiguration.newBuilder()
                                                          .setName("route")
                                                          .addVirtualHosts(virtualHost)
                                                          .build())
                        .addHttpFilters(HttpFilter.newBuilder()
                                                  .setName("envoy.filters.http.router")
                                                  .setTypedConfig(Any.pack(Router.getDefaultInstance())))
                        .build();
        return Listener.newBuilder()
                       .setName("listener")
                       .setApiListener(ApiListener.newBuilder().setApiListener(Any.pack(manager)))
                       .build();
    }

    static Metadata metadata(Struct struct) {
        return Metadata.newBuilder().putFilterMetadata(SUBSET_LOAD_BALANCING_FILTER_NAME, struct)
                       .build();
    }

    static Metadata metadata(Map<String, String> map) {
        return metadata(struct(map));
    }

    static Value fallbackListValue(Map<String, String>... maps) {
        final List<Value> values =
                Arrays.stream(maps).map(map -> Value.newBuilder()
                                                    .setStructValue(struct(map))
                                                    .build()).collect(Collectors.toList());
        return Value.newBuilder()
                    .setListValue(ListValue.newBuilder()
                                           .addAllValues(values)
                                           .build())
                    .build();
    }

    static Struct struct(Map<String, String> map) {
        final Map<String, Value> structMap =
                map.entrySet().stream()
                   .collect(Collectors.toMap(Entry::getKey,
                                             e -> Value.newBuilder()
                                                       .setStringValue(e.getValue()).build()));
        return Struct.newBuilder().putAllFields(structMap).build();
    }

    static LbSubsetSelector lbSubsetSelector(Iterable<String> keys) {
        return LbSubsetSelector.newBuilder()
                               .addAllKeys(keys)
                               .build();
    }

    static LbSubsetConfig lbSubsetConfig(LbSubsetSelector... lbSubsetSelectors) {
        return LbSubsetConfig.newBuilder()
                             .addAllSubsetSelectors(Arrays.asList(lbSubsetSelectors))
                             .build();
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

    private EndpointTestUtil() {}
}

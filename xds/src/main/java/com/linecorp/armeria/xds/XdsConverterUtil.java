/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.xds.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;

import com.google.common.base.Strings;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;

final class XdsConverterUtil {

    private XdsConverterUtil() {}

    static List<Endpoint> convertEndpoints(ClusterLoadAssignment clusterLoadAssignment) {
        return convertEndpoints(clusterLoadAssignment, lbEndpoint -> true);
    }

    static List<Endpoint> convertEndpoints(ClusterLoadAssignment clusterLoadAssignment, Struct filterMetadata) {
        checkArgument(filterMetadata.getFieldsCount() > 0,
                      "filterMetadata.getFieldsCount(): %s (expected: > 0)", filterMetadata.getFieldsCount());
        final Predicate<LbEndpoint> lbEndpointPredicate = lbEndpoint -> {
            final Struct endpointMetadata = lbEndpoint.getMetadata().getFilterMetadataOrDefault(
                    SUBSET_LOAD_BALANCING_FILTER_NAME, Struct.getDefaultInstance());
            if (endpointMetadata.getFieldsCount() == 0) {
                return false;
            }
            return containsFilterMetadata(filterMetadata, endpointMetadata);
        };
        return convertEndpoints(clusterLoadAssignment, lbEndpointPredicate);
    }

    private static List<Endpoint> convertEndpoints(ClusterLoadAssignment clusterLoadAssignment,
                                                   Predicate<LbEndpoint> lbEndpointPredicate) {
        return clusterLoadAssignment.getEndpointsList().stream().flatMap(
                localityLbEndpoints -> localityLbEndpoints
                        .getLbEndpointsList()
                        .stream()
                        .filter(lbEndpointPredicate)
                        .map(lbEndpoint -> {
                            final SocketAddress socketAddress =
                                    lbEndpoint.getEndpoint().getAddress().getSocketAddress();
                            final String hostname = lbEndpoint.getEndpoint().getHostname();
                            if (!Strings.isNullOrEmpty(hostname)) {
                                return Endpoint.of(hostname, socketAddress.getPortValue())
                                               .withIpAddr(socketAddress.getAddress());
                            } else {
                                return Endpoint.of(socketAddress.getAddress(), socketAddress.getPortValue());
                            }
                        })).collect(toImmutableList());
    }

    private static boolean containsFilterMetadata(Struct filterMetadata, Struct endpointMetadata) {
        final Map<String, Value> endpointMetadataMap = endpointMetadata.getFieldsMap();
        for (Entry<String, Value> entry : filterMetadata.getFieldsMap().entrySet()) {
            final Value value = endpointMetadataMap.get(entry.getKey());
            if (value == null || !value.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    static void validateConfigSource(@Nullable ConfigSource configSource) {
        if (configSource == null || configSource.equals(ConfigSource.getDefaultInstance())) {
            return;
        }
        checkArgument(configSource.hasAds() || configSource.hasApiConfigSource(),
                      "Only configSource with Ads or ApiConfigSource is supported for %s", configSource);
        if (configSource.hasApiConfigSource()) {
            final ApiConfigSource apiConfigSource = configSource.getApiConfigSource();
            final ApiType apiType = apiConfigSource.getApiType();
            checkArgument(apiType == ApiType.GRPC || apiType == ApiType.AGGREGATED_GRPC,
                          "Unsupported apiType %s. Only GRPC and AGGREGATED_GRPC are supported.", configSource);
            checkArgument(apiConfigSource.getGrpcServicesCount() > 0,
                          "At least once GrpcService is required for ApiConfigSource for %s", configSource);
            apiConfigSource.getGrpcServicesList().forEach(
                    grpcService -> checkArgument(grpcService.hasEnvoyGrpc(),
                                                 "Only envoyGrpc is supported for %s", grpcService));
        }
    }
}

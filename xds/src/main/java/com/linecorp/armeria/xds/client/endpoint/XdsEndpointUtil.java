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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.xds.client.endpoint.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;

import com.google.common.base.Strings;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.Endpoint;

import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

final class XdsEndpointUtil {

    static List<Endpoint> convertEndpoints(List<Endpoint> endpoints, Struct filterMetadata) {
        checkArgument(filterMetadata.getFieldsCount() > 0,
                      "filterMetadata.getFieldsCount(): %s (expected: > 0)", filterMetadata.getFieldsCount());
        final Predicate<Endpoint> lbEndpointPredicate = endpoint -> {
            final LbEndpoint lbEndpoint = endpoint.attr(XdsAttributeKeys.LB_ENDPOINT_KEY);
            assert lbEndpoint != null;
            final Struct endpointMetadata = lbEndpoint.getMetadata().getFilterMetadataOrDefault(
                    SUBSET_LOAD_BALANCING_FILTER_NAME, Struct.getDefaultInstance());
            if (endpointMetadata.getFieldsCount() == 0) {
                return false;
            }
            return containsFilterMetadata(filterMetadata, endpointMetadata);
        };
        return endpoints.stream().filter(lbEndpointPredicate).collect(toImmutableList());
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

    static List<Endpoint> convertLoadAssignment(ClusterLoadAssignment clusterLoadAssignment) {
        return clusterLoadAssignment.getEndpointsList().stream().flatMap(
                                            localityLbEndpoints -> localityLbEndpoints
                                                    .getLbEndpointsList()
                                                    .stream()
                                                    .map(lbEndpoint -> convertToEndpoint(localityLbEndpoints,
                                                                                         lbEndpoint)))
                                    .collect(toImmutableList());
    }

    private static Endpoint convertToEndpoint(LocalityLbEndpoints localityLbEndpoints, LbEndpoint lbEndpoint) {
        final SocketAddress socketAddress =
                lbEndpoint.getEndpoint().getAddress().getSocketAddress();
        final String hostname = lbEndpoint.getEndpoint().getHostname();
        final int weight = EndpointUtil.endpointWeight(lbEndpoint);
        final Endpoint endpoint;
        if (!Strings.isNullOrEmpty(hostname)) {
            endpoint = Endpoint.of(hostname)
                               .withIpAddr(socketAddress.getAddress())
                               .withAttr(XdsAttributeKeys.LB_ENDPOINT_KEY, lbEndpoint)
                               .withAttr(XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY, localityLbEndpoints)
                               .withWeight(weight);
        } else {
            endpoint = Endpoint.of(socketAddress.getAddress())
                               .withAttr(XdsAttributeKeys.LB_ENDPOINT_KEY, lbEndpoint)
                               .withAttr(XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY, localityLbEndpoints)
                               .withWeight(weight);
        }
        if (socketAddress.hasPortValue()) {
            return endpoint.withPort(socketAddress.getPortValue());
        }
        return endpoint;
    }

    private XdsEndpointUtil() {}
}

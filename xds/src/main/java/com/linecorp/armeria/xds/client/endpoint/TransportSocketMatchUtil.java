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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.xds.client.endpoint.XdsConstants.TRANSPORT_SOCKET_MATCH_FILTER_NAME;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.TransportSocketMatchSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;

import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

final class TransportSocketMatchUtil {

    static TransportSocketSnapshot selectTransportSocket(
            TransportSocketSnapshot transportSocket, List<TransportSocketMatchSnapshot> transportSocketMatches,
            @Nullable LbEndpoint lbEndpoint, @Nullable LocalityLbEndpoints localityLbEndpoints) {
        if (transportSocketMatches.isEmpty()) {
            return transportSocket;
        }
        TransportSocketMatchSnapshot matched = null;
        if (lbEndpoint != null) {
            matched = selectMatch(transportSocketMatches, endpointMatchMetadata(lbEndpoint));
        }
        if (matched == null && localityLbEndpoints != null) {
            matched = selectMatch(transportSocketMatches, localityMatchMetadata(localityLbEndpoints));
        }
        return matched != null ? matched.transportSocket() : transportSocket;
    }

    @Nullable
    static TransportSocketMatchSnapshot selectMatch(List<TransportSocketMatchSnapshot> matches,
                                                    Struct endpointMetadata) {
        for (TransportSocketMatchSnapshot match : matches) {
            if (matches(match.xdsResource().getMatch(), endpointMetadata)) {
                return match;
            }
        }
        return null;
    }

    @VisibleForTesting
    static boolean matches(Struct criteria, Struct endpointMetadata) {
        if (criteria.getFieldsCount() == 0) {
            return true;
        }
        if (endpointMetadata.getFieldsCount() == 0) {
            return false;
        }
        final Map<String, Value> metadataMap = endpointMetadata.getFieldsMap();
        for (Entry<String, Value> entry : criteria.getFieldsMap().entrySet()) {
            final Value value = metadataMap.get(entry.getKey());
            if (value == null || !value.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    static Struct endpointMatchMetadata(@Nullable LbEndpoint lbEndpoint) {
        if (lbEndpoint == null) {
            return Struct.getDefaultInstance();
        }
        return transportSocketMatchMetadata(lbEndpoint.getMetadata());
    }

    static Struct localityMatchMetadata(@Nullable LocalityLbEndpoints localityLbEndpoints) {
        if (localityLbEndpoints == null) {
            return Struct.getDefaultInstance();
        }
        return transportSocketMatchMetadata(localityLbEndpoints.getMetadata());
    }

    private static Struct transportSocketMatchMetadata(Metadata metadata) {
        return metadata.getFilterMetadataOrDefault(TRANSPORT_SOCKET_MATCH_FILTER_NAME,
                                                   Struct.getDefaultInstance());
    }

    private TransportSocketMatchUtil() {}
}

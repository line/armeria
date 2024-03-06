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

import static com.linecorp.armeria.xds.client.endpoint.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;

import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.Value.KindCase;

import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;

final class MetadataUtil {

    static boolean metadataLabelMatch(Struct labelSet, Metadata hostMetadata,
                                      String filterKey, boolean listAsAny) {
        if (hostMetadata == Metadata.getDefaultInstance()) {
            return labelSet.getFieldsMap().isEmpty();
        }
        if (!hostMetadata.containsFilterMetadata(filterKey)) {
            return labelSet.getFieldsMap().isEmpty();
        }
        final Struct dataStruct = hostMetadata.getFilterMetadataOrThrow(filterKey);
        for (Entry<String, Value> kv: labelSet.getFieldsMap().entrySet()) {
            if (!dataStruct.getFieldsMap().containsKey(kv.getKey())) {
                return false;
            }
            final Value value = dataStruct.getFieldsOrThrow(kv.getKey());
            if (listAsAny && value.getKindCase() == KindCase.LIST_VALUE) {
                boolean anyMatch = false;
                for (Value innerValue: value.getListValue().getValuesList()) {
                    if (Objects.equals(kv.getValue(), innerValue)) {
                        anyMatch = true;
                        break;
                    }
                }
                if (!anyMatch) {
                    return false;
                }
            } else if (!Objects.equals(kv.getValue(), value)) {
                return false;
            }
        }
        return true;
    }

    static Struct filterMetadata(ClusterSnapshot clusterSnapshot) {
        final Route route = clusterSnapshot.route();
        if (route == null) {
            return Struct.getDefaultInstance();
        }
        final RouteAction action = route.getRoute();
        final Struct metadata = action.getMetadataMatch().getFilterMetadataOrDefault(
                SUBSET_LOAD_BALANCING_FILTER_NAME,
                Struct.getDefaultInstance());
        if (!metadata.containsFields(XdsConstants.ENVOY_LB_FALLBACK_LIST)) {
            return metadata;
        }
        final Struct.Builder builder = Struct.newBuilder();
        for (Entry<String, Value> entry: metadata.getFieldsMap().entrySet()) {
            if (XdsConstants.ENVOY_LB_FALLBACK_LIST.equals(entry.getKey())) {
                continue;
            }
            builder.putFields(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    static List<Struct> fallbackMetadataList(ClusterSnapshot clusterSnapshot) {
        final Route route = clusterSnapshot.route();
        if (route == null) {
            return Collections.emptyList();
        }
        final RouteAction action = route.getRoute();
        final Struct metadata = action.getMetadataMatch().getFilterMetadataOrDefault(
                SUBSET_LOAD_BALANCING_FILTER_NAME,
                Struct.getDefaultInstance());
        if (!metadata.containsFields(XdsConstants.ENVOY_LB_FALLBACK_LIST)) {
            return Collections.emptyList();
        }
        final Value fallbackValue =
                metadata.getFieldsOrDefault(XdsConstants.ENVOY_LB_FALLBACK_LIST,
                                            Value.getDefaultInstance());
        if (!fallbackValue.hasListValue()) {
            return Collections.emptyList();
        }
        final ListValue fallbackListValue = fallbackValue.getListValue();
        final ImmutableList.Builder<Struct> fallbackMetadataList = ImmutableList.builder();
        for (Value value: fallbackListValue.getValuesList()) {
            if (value.hasStructValue()) {
                fallbackMetadataList.add(value.getStructValue());
            }
        }
        return fallbackMetadataList.build();
    }

    static Struct withFilterKeys(Struct filterMetadata, Set<String> subsetKeys) {
        final Struct.Builder structBuilder = Struct.newBuilder();
        for (Entry<String, Value> entry: filterMetadata.getFieldsMap().entrySet()) {
            if (subsetKeys.contains(entry.getKey())) {
                structBuilder.putFields(entry.getKey(), entry.getValue());
            }
        }
        return structBuilder.build();
    }

    private MetadataUtil() {}
}

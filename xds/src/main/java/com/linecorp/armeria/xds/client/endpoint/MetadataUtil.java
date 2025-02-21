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

import java.util.Map;

import com.google.protobuf.ProtocolStringList;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.xds.internal.XdsAttributeKeys;

import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector;
import io.envoyproxy.envoy.config.core.v3.Metadata;

final class MetadataUtil {

    static Struct filterMetadata(ClientRequestContext ctx) {
        final Metadata metadataMatch = ctx.attr(XdsAttributeKeys.ROUTE_METADATA_MATCH);
        if (metadataMatch == null) {
            return Struct.getDefaultInstance();
        }
        return metadataMatch.getFilterMetadataOrDefault(SUBSET_LOAD_BALANCING_FILTER_NAME,
                                                        Struct.getDefaultInstance());
    }

    static boolean findMatchedSubsetSelector(LbSubsetConfig lbSubsetConfig, Struct filterMetadata) {
        for (LbSubsetSelector subsetSelector : lbSubsetConfig.getSubsetSelectorsList()) {
            final ProtocolStringList keysList = subsetSelector.getKeysList();
            if (filterMetadata.getFieldsCount() != keysList.size()) {
                continue;
            }
            boolean found = true;
            final Map<String, Value> filterMetadataMap = filterMetadata.getFieldsMap();
            for (String key : filterMetadataMap.keySet()) {
                if (!keysList.contains(key)) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return true;
            }
        }
        return false;
    }

    private MetadataUtil() {}
}

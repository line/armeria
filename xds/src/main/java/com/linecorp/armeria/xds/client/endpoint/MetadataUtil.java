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

import com.google.protobuf.Struct;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.xds.internal.XdsAttributeKeys;

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

    private MetadataUtil() {}
}

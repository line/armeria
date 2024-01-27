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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import com.linecorp.armeria.common.annotation.Nullable;

final class XdsResourceParserUtil {

    private static final Map<String, ResourceParser> typeUrlToResourceType = new HashMap<>();
    private static final Map<XdsType, ResourceParser> typeToResourceType = new EnumMap<>(XdsType.class);

    static {
        typeUrlToResourceType.put(XdsType.ENDPOINT.typeUrl(), EndpointResourceParser.INSTANCE);
        typeUrlToResourceType.put(XdsType.CLUSTER.typeUrl(), ClusterResourceParser.INSTANCE);
        typeUrlToResourceType.put(XdsType.LISTENER.typeUrl(), ListenerResourceParser.INSTANCE);
        typeUrlToResourceType.put(XdsType.ROUTE.typeUrl(), RouteResourceParser.INSTANCE);

        typeToResourceType.put(XdsType.ENDPOINT, EndpointResourceParser.INSTANCE);
        typeToResourceType.put(XdsType.CLUSTER, ClusterResourceParser.INSTANCE);
        typeToResourceType.put(XdsType.LISTENER, ListenerResourceParser.INSTANCE);
        typeToResourceType.put(XdsType.ROUTE, RouteResourceParser.INSTANCE);
    }

    @Nullable
    static ResourceParser fromTypeUrl(String typeUrl) {
        return typeUrlToResourceType.get(typeUrl);
    }

    static ResourceParser fromType(XdsType xdsType) {
        return typeToResourceType.get(xdsType);
    }

    private XdsResourceParserUtil() {}
}

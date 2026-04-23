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

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;

final class XdsResourceParserUtil {

    private static final Map<String, ResourceParser<?, ?>> typeUrlToParser;
    private static final Map<XdsType, ResourceParser<?, ?>> typeToParser;

    static {
        final ImmutableMap.Builder<String, ResourceParser<?, ?>> byTypeUrl = ImmutableMap.builder();
        final ImmutableMap.Builder<XdsType, ResourceParser<?, ?>> byType = ImmutableMap.builder();

        register(ListenerResourceParser.INSTANCE, byTypeUrl, byType);
        register(ClusterResourceParser.INSTANCE, byTypeUrl, byType);
        register(RouteResourceParser.INSTANCE, byTypeUrl, byType);
        register(EndpointResourceParser.INSTANCE, byTypeUrl, byType);
        register(SecretResourceParser.INSTANCE, byTypeUrl, byType);

        typeUrlToParser = byTypeUrl.build();
        typeToParser = byType.build();
    }

    private static void register(ResourceParser<?, ?> parser,
                                 ImmutableMap.Builder<String, ResourceParser<?, ?>> byTypeUrl,
                                 ImmutableMap.Builder<XdsType, ResourceParser<?, ?>> byType) {
        byTypeUrl.put(parser.type().typeUrl(), parser);
        byType.put(parser.type(), parser);
    }

    @Nullable
    static ResourceParser<?, ?> fromTypeUrl(String typeUrl) {
        return typeUrlToParser.get(typeUrl);
    }

    @Nullable
    static ResourceParser<?, ?> fromType(XdsType type) {
        return typeToParser.get(type);
    }

    private XdsResourceParserUtil() {}
}

/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;
import com.google.protobuf.util.JsonFormat.TypeRegistry;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

public final class XdsResourceReader {

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    private static final Parser parser =
            JsonFormat.parser().usingTypeRegistry(TypeRegistry.newBuilder()
                                                              .add(HttpConnectionManager.getDescriptor())
                                                              .add(Router.getDescriptor())
                                                              .build());

    public static Bootstrap fromYaml(String yaml) {
        final Bootstrap.Builder bootstrapBuilder = Bootstrap.newBuilder();
        try {
            final JsonNode jsonNode = mapper.reader().readTree(yaml);
            parser.merge(jsonNode.toString(), bootstrapBuilder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return bootstrapBuilder.build();
    }

    @SuppressWarnings("unchecked")
    public static <T extends GeneratedMessageV3> T fromYaml(String yaml, Class<T> clazz) {
        final GeneratedMessageV3.Builder<?> builder;
        try {
            builder = (GeneratedMessageV3.Builder<?>) clazz.getMethod("newBuilder").invoke(null);
            final JsonNode jsonNode = mapper.reader().readTree(yaml);
            parser.merge(jsonNode.toString(), builder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return (T) builder.build();
    }

    private XdsResourceReader() {}
}

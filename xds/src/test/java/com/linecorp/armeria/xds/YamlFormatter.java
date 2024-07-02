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

package com.linecorp.armeria.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;
import com.google.protobuf.util.JsonFormat.TypeRegistry;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

public final class YamlFormatter {

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    private static final Parser parser =
            JsonFormat.parser().usingTypeRegistry(TypeRegistry.newBuilder()
                                                              .add(HttpConnectionManager.getDescriptor())
                                                              .add(Router.getDescriptor())
                                                              .build());

    public static Listener formatResource(String resourceName) throws IOException, URISyntaxException {
        final URL resource = YamlFormatter.class.getResource(resourceName);
        checkNotNull(resource, "Couldn't find resource (%s)", resourceName);
        final byte[] bytes = Files.readAllBytes(Paths.get(resource.toURI()));
        final JsonNode jsonNode = mapper.reader().readTree(bytes);
        final Listener.Builder builder = Listener.newBuilder();
        parser.merge(jsonNode.toString(), builder);
        return builder.build();
    }

    private YamlFormatter() {}
}

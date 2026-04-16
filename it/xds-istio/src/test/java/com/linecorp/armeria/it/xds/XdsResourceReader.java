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

package com.linecorp.armeria.it.xds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;
import com.google.protobuf.util.JsonFormat.TypeRegistry;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.access_loggers.file.v3.FileAccessLog;
import io.envoyproxy.envoy.extensions.compression.brotli.compressor.v3.Brotli;
import io.envoyproxy.envoy.extensions.compression.gzip.compressor.v3.Gzip;
import io.envoyproxy.envoy.extensions.compression.zstd.compressor.v3.Zstd;
import io.envoyproxy.envoy.extensions.filters.http.compressor.v3.Compressor;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.resource_monitors.downstream_connections.v3.DownstreamConnectionsConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;

public final class XdsResourceReader {

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    // ignoringUnknownFields() lets the parser skip proto fields that have no
    // matching descriptor — callers are responsible for stripping Any-typed
    // extension fields (e.g. typedExtensionProtocolOptions) before parsing so
    // that no additional TypeRegistry entries are needed here.
    private static final Parser parser =
            JsonFormat.parser()
                      .ignoringUnknownFields()
                      .usingTypeRegistry(TypeRegistry.newBuilder()
                                                      .add(com.github.udpa.udpa.type.v1.TypedStruct
                                                               .getDescriptor())
                                                      .add(com.github.xds.type.v3.TypedStruct.getDescriptor())
                                                      .add(FileAccessLog.getDescriptor())
                                                      .add(HttpProtocolOptions.getDescriptor())
                                                      .add(Compressor.getDescriptor())
                                                      .add(Brotli.getDescriptor())
                                                      .add(Zstd.getDescriptor())
                                                      .add(Gzip.getDescriptor())
                                                      .add(DownstreamConnectionsConfig.getDescriptor())
                                                      .add(HttpConnectionManager.getDescriptor())
                                                      .add(Router.getDescriptor())
                                                      .add(UpstreamTlsContext.getDescriptor())
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
    public static <T extends Message> T fromYaml(String yaml, Class<T> clazz) {
        final Message.Builder builder;
        try {
            builder = (Message.Builder) clazz.getMethod("newBuilder").invoke(null);
            final JsonNode jsonNode = mapper.reader().readTree(yaml);
            parser.merge(jsonNode.toString(), builder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return (T) builder.build();
    }

    @SuppressWarnings("unchecked")
    public static <T extends Message> T fromJson(String json, Class<T> clazz) {
        final Message.Builder builder;
        try {
            builder = (Message.Builder) clazz.getMethod("newBuilder").invoke(null);
            final JsonNode jsonNode = jsonMapper.reader().readTree(json);
            parser.merge(jsonNode.toString(), builder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return (T) builder.build();
    }

    private static final Escaper multiLineEscaper = Escapers.builder()
                                                            .addEscape('\\', "\\\\")
                                                            .addEscape('"', "\\\"")
                                                            .addEscape('\n', "\\n")
                                                            .addEscape('\r', "\\r")
                                                            .build();

    static String escapeMultiLine(String str) {
        return multiLineEscaper.escape(str);
    }

    private XdsResourceReader() {}
}

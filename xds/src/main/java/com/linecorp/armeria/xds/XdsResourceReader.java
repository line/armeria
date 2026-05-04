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

package com.linecorp.armeria.xds;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;
import com.google.protobuf.util.JsonFormat.TypeRegistry;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A utility for reading xDS resources from YAML or JSON.
 *
 * <p>All well-known Envoy protobuf types (under the {@code io.envoyproxy} package) are
 * automatically registered so that {@code @type} fields in YAML/JSON are resolved correctly.
 *
 * <p>Since YAML is a superset of JSON, all methods accept both formats.
 *
 * <p>Example usage:
 * <pre>{@code
 * Cluster cluster = XdsResourceReader.from(yamlOrJson, Cluster.class);
 * }</pre>
 */
@UnstableApi
public final class XdsResourceReader {

    private static final Logger logger = LoggerFactory.getLogger(XdsResourceReader.class);

    // The YAML mapper handles both YAML and JSON since JSON is a subset of YAML.
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private static final class DefaultParserHolder {
        static final Parser INSTANCE = JsonFormat.parser().usingTypeRegistry(buildDefaultTypeRegistry());

        private static TypeRegistry buildDefaultTypeRegistry() {
            final TypeRegistry.Builder builder = TypeRegistry.newBuilder();
            final ConfigurationBuilder configuration = new ConfigurationBuilder()
                    .setUrls(ClasspathHelper.forPackage("io.envoyproxy"))
                    .setScanners(new SubTypesScanner())
                    .filterInputsBy(new FilterBuilder().include(
                            FilterBuilder.prefix("io.envoyproxy")));
            final Reflections reflections = new Reflections(configuration);
            for (Class<?> clazz : reflections.getSubTypesOf(GeneratedMessageV3.class)) {
                try {
                    final Descriptor descriptor =
                            (Descriptor) clazz.getMethod("getDescriptor").invoke(null);
                    builder.add(descriptor);
                } catch (Exception e) {
                    logger.warn("Failed to register descriptor for {}", clazz.getName(), e);
                }
            }
            return builder.build();
        }
    }

    /**
     * Reads a protobuf message of the specified type from the given YAML or JSON string.
     */
    public static <T extends GeneratedMessageV3> T from(String yamlOrJson, Class<T> clazz) {
        requireNonNull(yamlOrJson, "yamlOrJson");
        requireNonNull(clazz, "clazz");
        return parse(yamlOrJson, clazz, DefaultParserHolder.INSTANCE);
    }

    /**
     * Reads a protobuf message of the specified type from a YAML or JSON file at the given path.
     */
    public static <T extends GeneratedMessageV3> T fromFile(Path path, Class<T> clazz) {
        requireNonNull(path, "path");
        requireNonNull(clazz, "clazz");
        return from(readFile(path), clazz);
    }

    /**
     * Reads a protobuf message of the specified type from a YAML or JSON file at the given path.
     */
    public static <T extends GeneratedMessageV3> T fromFile(String path, Class<T> clazz) {
        requireNonNull(path, "path");
        requireNonNull(clazz, "clazz");
        return fromFile(Paths.get(path), clazz);
    }

    @SuppressWarnings("unchecked")
    private static <T extends GeneratedMessageV3> T parse(String content, Class<T> clazz,
                                                          Parser parser) {
        final GeneratedMessageV3.Builder<?> builder;
        try {
            builder = (GeneratedMessageV3.Builder<?>) clazz.getMethod("newBuilder").invoke(null);
            final JsonNode jsonNode = yamlMapper.reader().readTree(content);
            parser.merge(jsonNode.toString(), builder);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse as " + clazz.getSimpleName(), e);
        }
        return (T) builder.build();
    }

    private static String readFile(Path path) {
        try {
            final byte[] bytes = Files.readAllBytes(path);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file: " + path, e);
        }
    }

    private XdsResourceReader() {}
}

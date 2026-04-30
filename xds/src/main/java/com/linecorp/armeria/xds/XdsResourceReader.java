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

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

/**
 * A utility for reading xDS resources from YAML or JSON.
 *
 * <p>All well-known Envoy protobuf types (under the {@code io.envoyproxy} package) are
 * automatically registered so that {@code @type} fields in YAML/JSON are resolved correctly.
 *
 * <p>Example usage:
 * <pre>{@code
 * Bootstrap bootstrap = XdsResourceReader.fromYamlFile("bootstrap.yaml");
 * }</pre>
 */
@UnstableApi
public final class XdsResourceReader {

    private static final Logger logger = LoggerFactory.getLogger(XdsResourceReader.class);

    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    private static final class DefaultParserHolder {
        static final Parser INSTANCE = JsonFormat.parser().usingTypeRegistry(buildDefaultTypeRegistry());

        private static TypeRegistry buildDefaultTypeRegistry() {
            final TypeRegistry.Builder builder = TypeRegistry.newBuilder();
            final ConfigurationBuilder configuration = new ConfigurationBuilder()
                    // list of jars containing extensions
                    .setUrls(ClasspathHelper.forPackage("io.envoyproxy.envoy.extensions"))
                    .setScanners(new SubTypesScanner())
                    // within each jar, only scan classes matching package name
                    .filterInputsBy(new FilterBuilder().include(
                            FilterBuilder.prefix("io.envoyproxy.envoy.extensions")));
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
     * Reads a {@link Bootstrap} from the given YAML string.
     */
    public static Bootstrap fromYaml(String yaml) {
        requireNonNull(yaml, "yaml");
        return XdsResourceReader.<Bootstrap>parseYaml(yaml, Bootstrap.class,
                                                      DefaultParserHolder.INSTANCE);
    }

    /**
     * Reads a {@link Bootstrap} from the YAML file at the given path.
     */
    public static Bootstrap fromYamlFile(Path path) {
        requireNonNull(path, "path");
        return fromYaml(readFile(path));
    }

    /**
     * Reads a {@link Bootstrap} from the YAML file at the given path.
     */
    public static Bootstrap fromYamlFile(String path) {
        requireNonNull(path, "path");
        return fromYamlFile(Paths.get(path));
    }

    /**
     * Reads a protobuf message of the specified type from the given YAML string.
     */
    public static <T extends GeneratedMessageV3> T fromYaml(String yaml, Class<T> clazz) {
        requireNonNull(yaml, "yaml");
        requireNonNull(clazz, "clazz");
        return parseYaml(yaml, clazz, DefaultParserHolder.INSTANCE);
    }

    /**
     * Reads a {@link Bootstrap} from the given JSON string.
     */
    public static Bootstrap fromJson(String json) {
        requireNonNull(json, "json");
        return XdsResourceReader.<Bootstrap>parseJson(json, Bootstrap.class,
                                                      DefaultParserHolder.INSTANCE);
    }

    /**
     * Reads a {@link Bootstrap} from the JSON file at the given path.
     */
    public static Bootstrap fromJsonFile(Path path) {
        requireNonNull(path, "path");
        return fromJson(readFile(path));
    }

    /**
     * Reads a {@link Bootstrap} from the JSON file at the given path.
     */
    public static Bootstrap fromJsonFile(String path) {
        requireNonNull(path, "path");
        return fromJsonFile(Paths.get(path));
    }

    /**
     * Reads a protobuf message of the specified type from the given JSON string.
     */
    public static <T extends GeneratedMessageV3> T fromJson(String json, Class<T> clazz) {
        requireNonNull(json, "json");
        requireNonNull(clazz, "clazz");
        return parseJson(json, clazz, DefaultParserHolder.INSTANCE);
    }

    @SuppressWarnings("unchecked")
    private static <T extends GeneratedMessageV3> T parseYaml(String yaml, Class<T> clazz,
                                                              Parser parser) {
        final GeneratedMessageV3.Builder<?> builder;
        try {
            builder = (GeneratedMessageV3.Builder<?>) clazz.getMethod("newBuilder").invoke(null);
            final JsonNode jsonNode = yamlMapper.reader().readTree(yaml);
            parser.merge(jsonNode.toString(), builder);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse YAML as " + clazz.getSimpleName(), e);
        }
        return (T) builder.build();
    }

    @SuppressWarnings("unchecked")
    private static <T extends GeneratedMessageV3> T parseJson(String json, Class<T> clazz,
                                                              Parser parser) {
        final GeneratedMessageV3.Builder<?> builder;
        try {
            builder = (GeneratedMessageV3.Builder<?>) clazz.getMethod("newBuilder").invoke(null);
            final JsonNode jsonNode = jsonMapper.reader().readTree(json);
            parser.merge(jsonNode.toString(), builder);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON as " + clazz.getSimpleName(), e);
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

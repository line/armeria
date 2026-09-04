/*
 * Copyright 2026 LY Corporation
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
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ServiceLoader;

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
import com.google.common.io.Resources;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;
import com.google.protobuf.util.JsonFormat.TypeRegistry;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A utility for reading xDS resources from YAML or JSON.
 *
 * <p>All well-known Envoy protobuf types (under the {@code io.envoyproxy}, {@code com.github.udpa}
 * and {@code com.github.xds} packages) are automatically registered so that {@code @type} fields
 * in YAML/JSON are resolved correctly. Additional packages can be registered via
 * the {@link XdsTypeRegistryPackageProvider} SPI.
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

    private static final class DefaultTypeRegistryHolder {
        static final TypeRegistry TYPE_REGISTRY = buildDefaultTypeRegistry();
        static final Parser PARSER = JsonFormat.parser()
                                               .ignoringUnknownFields()
                                               .usingTypeRegistry(TYPE_REGISTRY);

        private static TypeRegistry buildDefaultTypeRegistry() {
            final TypeRegistry.Builder builder = TypeRegistry.newBuilder();
            final FilterBuilder filterBuilder = new FilterBuilder();
            final ConfigurationBuilder configuration = new ConfigurationBuilder()
                    .setScanners(new SubTypesScanner());

            // Built-in packages
            final String[] defaultPackages = {
                    "io.envoyproxy",
                    "com.github.udpa",
                    "com.github.xds",
                    };
            for (String pkg : defaultPackages) {
                addPackage(pkg, configuration, filterBuilder);
            }

            // SPI-provided packages
            for (XdsTypeRegistryPackageProvider provider
                    : ServiceLoader.load(XdsTypeRegistryPackageProvider.class)) {
                final Iterable<String> packages = provider.packages();
                requireNonNull(packages, "packages");
                for (String pkg : packages) {
                    requireNonNull(pkg, "pkg");
                    addPackage(pkg, configuration, filterBuilder);
                }
            }

            configuration.filterInputsBy(filterBuilder);
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

        private static void addPackage(String pkg, ConfigurationBuilder configuration,
                                       FilterBuilder filterBuilder) {
            configuration.addUrls(ClasspathHelper.forPackage(pkg));
            filterBuilder.include(FilterBuilder.prefix(pkg));
        }
    }

    /**
     * Returns the default {@link TypeRegistry} containing all well-known Envoy protobuf types.
     * This can be used to create a {@link com.google.protobuf.util.JsonFormat.Printer} that
     * correctly serializes {@code Any}-wrapped xDS messages.
     */
    public static TypeRegistry typeRegistry() {
        return DefaultTypeRegistryHolder.TYPE_REGISTRY;
    }

    /**
     * Reads a protobuf message of the specified type from the given YAML or JSON string.
     * Unknown fields are silently ignored so that bootstrap configurations generated by
     * external tools (e.g. Istio, gRPC) can be parsed without errors.
     */
    public static <T extends GeneratedMessageV3> T from(String yamlOrJson, Class<T> clazz) {
        requireNonNull(yamlOrJson, "yamlOrJson");
        requireNonNull(clazz, "clazz");
        return parse(yamlOrJson, clazz, DefaultTypeRegistryHolder.PARSER);
    }

    /**
     * Reads a protobuf message of the specified type from the given {@link URL}.
     * This works uniformly for classpath resources, file paths and remote URLs.
     */
    public static <T extends GeneratedMessageV3> T from(URL url, Class<T> clazz) {
        requireNonNull(url, "url");
        requireNonNull(clazz, "clazz");
        return from(readUrl(url), clazz);
    }

    /**
     * Reads a protobuf message of the specified type from a YAML or JSON file at the given path.
     */
    public static <T extends GeneratedMessageV3> T fromFile(Path path, Class<T> clazz) {
        requireNonNull(path, "path");
        requireNonNull(clazz, "clazz");
        try {
            return from(path.toUri().toURL(), clazz);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid path: " + path, e);
        }
    }

    /**
     * Reads a protobuf message of the specified type from a YAML or JSON file at the given path.
     */
    public static <T extends GeneratedMessageV3> T fromFile(String path, Class<T> clazz) {
        requireNonNull(path, "path");
        requireNonNull(clazz, "clazz");
        return fromFile(Paths.get(path), clazz);
    }

    /**
     * Reads a protobuf message of the specified type from a classpath resource.
     * The resource is loaded via the context class loader.
     *
     * @throws IllegalArgumentException if the resource is not found on the classpath
     */
    public static <T extends GeneratedMessageV3> T fromResource(String resourceName, Class<T> clazz) {
        requireNonNull(resourceName, "resourceName");
        requireNonNull(clazz, "clazz");
        return fromResource(resourceName, XdsResourceReader.class.getClassLoader(), clazz);
    }

    /**
     * Reads a protobuf message of the specified type from a classpath resource.
     * The resource is loaded via the specified {@link ClassLoader}.
     *
     * @throws IllegalArgumentException if the resource is not found on the classpath
     */
    public static <T extends GeneratedMessageV3> T fromResource(String resourceName,
                                                                 ClassLoader classLoader,
                                                                 Class<T> clazz) {
        requireNonNull(resourceName, "resourceName");
        requireNonNull(classLoader, "classLoader");
        requireNonNull(clazz, "clazz");
        final URL url = classLoader.getResource(resourceName);
        if (url == null) {
            throw new IllegalArgumentException("Classpath resource not found: " + resourceName);
        }
        return from(url, clazz);
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

    private static String readUrl(URL url) {
        try {
            return Resources.toString(url, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read from: " + url, e);
        }
    }

    private XdsResourceReader() {}
}

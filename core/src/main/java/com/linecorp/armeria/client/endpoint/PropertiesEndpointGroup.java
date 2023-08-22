/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client.endpoint;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.FileWatcherRegistry.FileWatchRegisterKey;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link Properties} backed {@link EndpointGroup}. The list of {@link Endpoint}s are loaded from the
 * {@link Properties}.
 */
public final class PropertiesEndpointGroup extends DynamicEndpointGroup {
    private static FileWatcherRegistry registry = new FileWatcherRegistry();

    /**
     * Resets the registry for {@link PropertiesEndpointGroup}.
     * @throws Exception when an exception occurs while closing the {@link FileWatcherRegistry}.
     */
    @VisibleForTesting
    static void resetRegistry() throws Exception {
        registry.close();
        registry = new FileWatcherRegistry();
    }

    /**
     * Returns a new {@link PropertiesEndpointGroup} created from the specified classpath resource.
     * The value of each property whose name starts with {@code endpointKeyPrefix} will be parsed with
     * {@link Endpoint#parse(String)}, and then loaded into the {@link PropertiesEndpointGroup}, e.g.
     *
     * <pre>{@code
     * # endpointKeyPrefix = 'example.hosts.'
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param resourceName the name of the resource where the list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     */
    public static PropertiesEndpointGroup of(ClassLoader classLoader, String resourceName,
                                             String endpointKeyPrefix) {
        return builder(classLoader, resourceName, endpointKeyPrefix).build();
    }

    /**
     * Returns a new {@link PropertiesEndpointGroup} created from the specified {@link Properties}.
     * The value of each property whose name starts with {@code endpointKeyPrefix} will be parsed with
     * {@link Endpoint#parse(String)}, and then loaded into the {@link PropertiesEndpointGroup}, e.g.
     *
     * <pre>{@code
     * # endpointKeyPrefix = 'example.hosts.'
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param properties the {@link Properties} where the list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     */
    public static PropertiesEndpointGroup of(Properties properties, String endpointKeyPrefix) {
        return builder(properties, endpointKeyPrefix).build();
    }

    /**
     * Returns a new {@link PropertiesEndpointGroup} created from the file at the specified {@link Path}.
     * Any updates in the file will trigger a dynamic reload. The value of each property whose name starts
     * with {@code endpointKeyPrefix} will be parsed with {@link Endpoint#parse(String)}, and then loaded
     * into the {@link PropertiesEndpointGroup}, e.g.
     *
     * <pre>{@code
     * # endpointKeyPrefix = 'example.hosts.'
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param path the path of the file where list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     */
    public static PropertiesEndpointGroup of(Path path, String endpointKeyPrefix) {
        return builder(path, endpointKeyPrefix).build();
    }

    /**
     * Returns a new {@link PropertiesEndpointGroupBuilder} created from the specified classpath resource.
     * The value of each property whose name starts with {@code endpointKeyPrefix} will be parsed with
     * {@link Endpoint#parse(String)}, and then loaded into the {@link PropertiesEndpointGroup}, e.g.
     *
     * <pre>{@code
     * # endpointKeyPrefix = 'example.hosts.'
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param resourceName the name of the resource where the list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     */
    public static PropertiesEndpointGroupBuilder builder(ClassLoader classLoader, String resourceName,
                                                         String endpointKeyPrefix) {
        requireNonNull(classLoader, "classLoader");
        requireNonNull(resourceName, "resourceName");
        requireNonNull(endpointKeyPrefix, "endpointKeyPrefix");
        return new PropertiesEndpointGroupBuilder(classLoader, resourceName, endpointKeyPrefix);
    }

    /**
     * Returns a new {@link PropertiesEndpointGroupBuilder} created from the specified {@link Properties}.
     * The value of each property whose name starts with {@code endpointKeyPrefix} will be parsed with
     * {@link Endpoint#parse(String)}, and then loaded into the {@link PropertiesEndpointGroup}, e.g.
     *
     * <pre>{@code
     * # endpointKeyPrefix = 'example.hosts.'
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param properties the {@link Properties} where the list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     */
    public static PropertiesEndpointGroupBuilder builder(Properties properties, String endpointKeyPrefix) {
        requireNonNull(properties, "properties");
        requireNonNull(endpointKeyPrefix, "endpointKeyPrefix");
        return new PropertiesEndpointGroupBuilder(properties, endpointKeyPrefix);
    }

    /**
     * Returns a new {@link PropertiesEndpointGroupBuilder} created from the file at the specified
     * {@link Path}. Any updates in the file will trigger a dynamic reload. The value of each property
     * whose name starts with {@code endpointKeyPrefix} will be parsed with {@link Endpoint#parse(String)},
     * and then loaded into the {@link PropertiesEndpointGroup}, e.g.
     *
     * <pre>{@code
     * # endpointKeyPrefix = 'example.hosts.'
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param path the path of the file where list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     */
    public static PropertiesEndpointGroupBuilder builder(Path path, String endpointKeyPrefix) {
        requireNonNull(path, "path");
        requireNonNull(endpointKeyPrefix, "endpointKeyPrefix");
        return new PropertiesEndpointGroupBuilder(path, endpointKeyPrefix);
    }

    @Nullable
    private FileWatchRegisterKey watchRegisterKey;

    PropertiesEndpointGroup(EndpointSelectionStrategy selectionStrategy, List<Endpoint> endpoints) {
        super(selectionStrategy);
        setEndpoints(endpoints);
    }

    PropertiesEndpointGroup(EndpointSelectionStrategy selectionStrategy,
                            Path path, String endpointKeyPrefix, int defaultPort) {
        super(selectionStrategy);
        setEndpoints(loadEndpoints(
                path,
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                defaultPort));
        watchRegisterKey = registry.register(path, () ->
                setEndpoints(loadEndpoints(path, endpointKeyPrefix, defaultPort)));
    }

    private static List<Endpoint> loadEndpoints(Path path, String endpointKeyPrefix, int defaultPort) {
        try (InputStream in = Files.newInputStream(path)) {
            final Properties props = new Properties();
            props.load(in);
            return loadEndpoints(props, endpointKeyPrefix, defaultPort);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to load: " + path, e);
        }
    }

    private static List<Endpoint> loadEndpoints(Properties properties, String endpointKeyPrefix,
                                                int defaultPort) {
        if (!endpointKeyPrefix.endsWith(".")) {
            endpointKeyPrefix += ".";
        }
        final List<Endpoint> newEndpoints = new ArrayList<>();
        for (Entry<Object, Object> e : properties.entrySet()) {
            final String key = (String) e.getKey();
            final String value = (String) e.getValue();

            if (key.startsWith(endpointKeyPrefix)) {
                final Endpoint endpoint = Endpoint.parse(value);
                newEndpoints.add(defaultPort == 0 ? endpoint : endpoint.withDefaultPort(defaultPort));
            }
        }
        return ImmutableList.copyOf(newEndpoints);
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        if (watchRegisterKey != null) {
            registry.unregister(watchRegisterKey);
        }
        future.complete(null);
    }

    @Override
    public String toString() {
        return toString(buf -> buf.append(", watchRegisterKey=").append(watchRegisterKey));
    }
}

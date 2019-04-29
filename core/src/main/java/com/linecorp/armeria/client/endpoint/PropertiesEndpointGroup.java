/*
 * Copyright 2018 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

/**
 * A {@link Properties} backed {@link EndpointGroup}. The list of {@link Endpoint}s are loaded from the
 * {@link Properties}.
 */
public final class PropertiesEndpointGroup extends DynamicEndpointGroup {

    // TODO(ide) Reload the endpoint list if the file is updated.

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@code fileName} resource file. The resource file must
     * contain at least one property whose name starts with {@code endpointKeyPrefix}:
     *
     * <pre>{@code
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param resourceName the name of the resource where the list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     *
     * @throws IllegalArgumentException if failed to load any hosts from the specified resource file
     */
    public static PropertiesEndpointGroup of(ClassLoader classLoader, String resourceName,
                                             String endpointKeyPrefix) {
        return new PropertiesEndpointGroup(classLoader, resourceName, endpointKeyPrefix, 0);
    }

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@code fileName} resource file. The resource file must
     * contain at least one property whose name starts with {@code endpointKeyPrefix}:
     *
     * <pre>{@code
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param resourceName the name of the resource where the list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     * @param defaultPort the default port number to use
     *
     * @throws IllegalArgumentException if failed to load any hosts from the specified resource file
     */
    public static PropertiesEndpointGroup of(ClassLoader classLoader, String resourceName,
                                             String endpointKeyPrefix, int defaultPort) {
        validateDefaultPort(defaultPort);
        return new PropertiesEndpointGroup(classLoader, resourceName, endpointKeyPrefix, 0);
    }

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@link Properties}. The {@link Properties} must contain at
     * least one property whose name starts with {@code endpointKeyPrefix}:
     *
     * <pre>{@code
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param properties the {@link Properties} where the list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     *
     * @throws IllegalArgumentException if failed to load any hosts from the specified {@link Properties}
     */
    public static PropertiesEndpointGroup of(Properties properties, String endpointKeyPrefix) {
        return new PropertiesEndpointGroup(loadEndpoints(
                requireNonNull(properties, "properties"),
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                0));
    }

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@link Properties}. The {@link Properties} must contain at
     * least one property whose name starts with {@code endpointKeyPrefix}:
     *
     * <pre>{@code
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param properties the {@link Properties} where the list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     * @param defaultPort the default port number to use
     *
     * @throws IllegalArgumentException if failed to load any hosts from the specified {@link Properties}
     */
    public static PropertiesEndpointGroup of(Properties properties, String endpointKeyPrefix,
                                             int defaultPort) {
        validateDefaultPort(defaultPort);
        return new PropertiesEndpointGroup(loadEndpoints(
                requireNonNull(properties, "properties"),
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                defaultPort));
    }

    public static class WatchPropertiesRunnable implements Runnable {
        final WatchService watchService;
        private final Map<URL, WatchKey> urlWatchKeyMap = new HashMap<>();
        private final Map<WatchKey, Path> watchKeyPathMap = new HashMap<>();

        WatchPropertiesRunnable() {
            try {
                watchService = FileSystems.getDefault().newWatchService();
            } catch (IOException e) {
                throw new RuntimeException("Failed to register watch service");
            }
        }

        public void registerResourceUrl(URL resourcePathUrl) {
            final File file = new File(resourcePathUrl.getFile());
            final Path path = file.getParentFile().toPath();
            try {
                final WatchKey key = path.register(watchService, ENTRY_MODIFY); // can we register multiple paths?
                urlWatchKeyMap.put(resourcePathUrl, key);
                watchKeyPathMap.put(key, file.toPath());
            } catch (IOException e) {
                throw new RuntimeException("Failed to register path");
            }
        }

        public void deregisterResourceUrl(URL resourcePathUrl) {
            final WatchKey key = urlWatchKeyMap.get(resourcePathUrl);
            key.cancel();
            urlWatchKeyMap.remove(resourcePathUrl);
            watchKeyPathMap.remove(key);
        }

        @Override
        public void run() {
            try {
                WatchKey key;
                while ((key = watchService.take()) != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        final Path path = watchKeyPathMap.get(key);
                        final Path changedPath = (Path) event.context();
                        if (event.kind() == ENTRY_MODIFY) {
                            final PropertiesEndpointGroup group = endpointGroupMap.get(changedPath.toFile().getName());
                            group.reload();
                        }
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("Unexpected exception while watching");
            }
        }
    }

    private static List<Endpoint> loadEndpoints(ClassLoader classLoader, String resourceName,
                                                String endpointKeyPrefix, int defaultPort) {

        final URL resourceUrl = classLoader.getResource(resourceName);
        checkArgument(resourceUrl != null, "resource not found: %s", resourceName);
        if (!endpointKeyPrefix.endsWith(".")) {
            endpointKeyPrefix += ".";
        }

        return read(resourceUrl, endpointKeyPrefix, defaultPort, resourceName);
    }

    private static List<Endpoint> loadEndpoints(Properties properties, String endpointKeyPrefix,
                                                int defaultPort) {
        final List<Endpoint> newEndpoints = new ArrayList<>();
        for (Entry<Object, Object> e : properties.entrySet()) {
            final String key = (String) e.getKey();
            final String value = (String) e.getValue();

            if (key.startsWith(endpointKeyPrefix)) {
                final Endpoint endpoint = Endpoint.parse(value);
                checkState(!endpoint.isGroup(),
                           "properties contains an endpoint group which is not allowed: %s in %s",
                           value, properties);
                newEndpoints.add(defaultPort == 0 ? endpoint : endpoint.withDefaultPort(defaultPort));
            }
        }
        checkArgument(!newEndpoints.isEmpty(), "properties contains no hosts: %s", properties);
        return ImmutableList.copyOf(newEndpoints);
    }

    private static List<Endpoint> read(URL resourceUrl, String endpointKeyPrefix,
                                       int defaultPort, String resourceName) {
        try (InputStream in = resourceUrl.openStream()) {
            final Properties props = new Properties();
            props.load(in);
            return loadEndpoints(props, endpointKeyPrefix, defaultPort);
        } catch (IOException e) {
              throw new IllegalArgumentException("failed to load: " + resourceName, e);
        }
    }

    private static void validateDefaultPort(int defaultPort) {
        checkArgument(defaultPort > 0 && defaultPort <= 65535,
                      "defaultPort: %s (expected: 1-65535)", defaultPort);
    }

    private ClassLoader classLoader;
    private String resourceName;
    private String endpointKeyPrefix;
    private int defaultPort;
    static WatchPropertiesRunnable runnable = new WatchPropertiesRunnable();
    private static final Thread thread = new Thread(runnable);

    static {
        thread.start();
    }

    private PropertiesEndpointGroup(List<Endpoint> endpoints) {
        setEndpoints(endpoints);
    }

    private PropertiesEndpointGroup(ClassLoader classLoader, String resourceName,
                                    String endpointKeyPrefix, int defaultPort) {
        final List<Endpoint> endpoints = loadEndpoints(
                requireNonNull(classLoader, "classLoader"),
                requireNonNull(resourceName, "fileName"),
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                0);
        setEndpoints(endpoints);
        this.classLoader = classLoader;
        this.resourceName = resourceName;
        this.endpointKeyPrefix = endpointKeyPrefix;
        this.defaultPort = defaultPort;
        final URL resourceUrl = classLoader.getResource(resourceName);
        endpointGroupMap.put(resourceName, this);
        runnable.registerResourceUrl(resourceUrl);

    }

    private void reload() {
        final List<Endpoint> endpointList = loadEndpoints(
                classLoader, resourceName, endpointKeyPrefix, defaultPort);
        setEndpoints(endpointList);
        System.out.println("endpointList: " + endpointList);
    }

    @Override
    public void close() {
        endpointGroupMap.remove(resourceName);
        final URL resourceUrl = classLoader.getResource(resourceName);
        runnable.deregisterResourceUrl(resourceUrl);
    }

    @VisibleForTesting
    static final Map<String, PropertiesEndpointGroup> endpointGroupMap = new HashMap<>();
}

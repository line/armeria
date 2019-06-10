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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.FileWatcherRegistry.FileWatchRegisterKey;

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
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@code resourceName} resource file. The resource file loads
     * properties whose name starts with {@code endpointKeyPrefix}:
     *
     * <pre>{@code
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
        final URL resourceUrl = getResourceUrl(
                requireNonNull(classLoader, "classLoader"),
                requireNonNull(resourceName, "resourceName"));
        return new PropertiesEndpointGroup(loadEndpoints(
                resourceUrl,
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                0));
    }

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@code resourceName} resource file. The resource file loads
     * properties whose name starts with {@code endpointKeyPrefix}:
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
     */
    public static PropertiesEndpointGroup of(ClassLoader classLoader, String resourceName,
                                             String endpointKeyPrefix, int defaultPort) {
        validateDefaultPort(defaultPort);
        final URL resourceUrl = getResourceUrl(
                requireNonNull(classLoader, "classLoader"),
                requireNonNull(resourceName, "resourceName"));
        return new PropertiesEndpointGroup(loadEndpoints(
                resourceUrl,
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                defaultPort));
    }

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@link Properties}. The {@link Properties} is filtered
     * to properties whose name starts with {@code endpointKeyPrefix}:
     *
     * <pre>{@code
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param properties the {@link Properties} where the list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     */
    public static PropertiesEndpointGroup of(Properties properties, String endpointKeyPrefix) {
        return new PropertiesEndpointGroup(loadEndpoints(
                requireNonNull(properties, "properties"),
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                0));
    }

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@link Properties}. The {@link Properties} is filtered
     * to properties whose name starts with {@code endpointKeyPrefix}:
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
     */
    public static PropertiesEndpointGroup of(Properties properties, String endpointKeyPrefix,
                                             int defaultPort) {
        validateDefaultPort(defaultPort);
        return new PropertiesEndpointGroup(loadEndpoints(
                requireNonNull(properties, "properties"),
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                defaultPort));
    }

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@code path} of a resource file. The resource file loads
     * properties whose name starts with {@code endpointKeyPrefix}. The {@code path} is watched for further
     * updates.
     *
     * <pre>{@code
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param path the path of the file where list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     * @param defaultPort the default port number to use
     */
    public static PropertiesEndpointGroup of(Path path, String endpointKeyPrefix, int defaultPort) {
        validateDefaultPort(defaultPort);
        return new PropertiesEndpointGroup(requireNonNull(path, "path"),
                                           requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                                           defaultPort);
    }

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@code path} of a resource file. The resource file loads
     * properties whose name starts with {@code endpointKeyPrefix}. The {@code path} is watched for
     * further updates.
     *
     * <pre>{@code
     * example.hosts.0=example1.com:36462
     * example.hosts.1=example2.com:36462
     * example.hosts.2=example3.com:36462
     * }</pre>
     *
     * @param path the path of the file where list of {@link Endpoint}s is loaded from
     * @param endpointKeyPrefix the property name prefix
     */
    public static PropertiesEndpointGroup of(Path path, String endpointKeyPrefix) {
        return new PropertiesEndpointGroup(requireNonNull(path, "path"),
                                           requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                                           0);
    }

    private static URL getResourceUrl(ClassLoader classLoader, String resourceName) {
        final URL resourceUrl = classLoader.getResource(resourceName);
        checkArgument(resourceUrl != null, "resource not found: %s", resourceName);
        return resourceUrl;
    }

    private static List<Endpoint> loadEndpoints(URL resourceUrl, String endpointKeyPrefix, int defaultPort) {
        try (InputStream in = resourceUrl.openStream()) {
            final Properties props = new Properties();
            props.load(in);
            return loadEndpoints(props, endpointKeyPrefix, defaultPort);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to load: " + resourceUrl.getFile(), e);
        }
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
                checkState(!endpoint.isGroup(),
                           "properties contains an endpoint group which is not allowed: %s in %s",
                           value, properties);
                newEndpoints.add(defaultPort == 0 ? endpoint : endpoint.withDefaultPort(defaultPort));
            }
        }
        return ImmutableList.copyOf(newEndpoints);
    }

    private static void validateDefaultPort(int defaultPort) {
        checkArgument(defaultPort > 0 && defaultPort <= 65535,
                      "defaultPort: %s (expected: 1-65535)", defaultPort);
    }

    @Nullable
    private FileWatchRegisterKey watchRegisterKey;

    private PropertiesEndpointGroup(List<Endpoint> endpoints) {
        setEndpoints(endpoints);
    }

    private PropertiesEndpointGroup(Path path, String endpointKeyPrefix, int defaultPort) {
        setEndpoints(loadEndpoints(
                path,
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                defaultPort));
        watchRegisterKey = registry.register(path, () ->
                setEndpoints(loadEndpoints(path, endpointKeyPrefix, defaultPort)));
    }

    @Override
    public void close() {
        try {
            super.close();
        } finally {
            if (watchRegisterKey != null) {
                registry.unregister(watchRegisterKey);
            }
        }
    }
}

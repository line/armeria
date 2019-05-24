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
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

/**
 * A {@link Properties} backed {@link EndpointGroup}. The list of {@link Endpoint}s are loaded from the
 * {@link Properties}.
 */
public final class PropertiesEndpointGroup extends DynamicEndpointGroup {
    @Nullable
    private Runnable closeCallback;
    private static final PropertiesFileWatcherRegistry registry =
            new PropertiesFileWatcherRegistry();

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@code resourceName} resource file. The resource file must
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
        final URL resourceUrl = getResourceUrl(
                requireNonNull(classLoader, "classLoader"),
                requireNonNull(resourceName, "fileName"));
        return new PropertiesEndpointGroup(loadEndpoints(
                resourceUrl,
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                0));
    }

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@code resourceName} resource file. The resource file must
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
        final URL resourceUrl = getResourceUrl(
                requireNonNull(classLoader, "classLoader"),
                requireNonNull(resourceName, "fileName"));
        return new PropertiesEndpointGroup(loadEndpoints(
                resourceUrl,
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                defaultPort));
    }

    /**
     * Creates a new {@link EndpointGroup} instance that loads the host names (or IP address) and the port
     * numbers of the {@link Endpoint} from the {@code resourceName} resource file. The resource file must
     * contain at least one property whose name starts with {@code endpointKeyPrefix}:. Reloading property files
     * for changes is false by default.
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
     * @param reloadable whether to watch and reload resource.
     *
     * @throws IllegalArgumentException if failed to load any hosts from the specified resource file
     */
    public static PropertiesEndpointGroup of(ClassLoader classLoader, String resourceName,
                                             String endpointKeyPrefix, int defaultPort,
                                             boolean reloadable) {
        validateDefaultPort(defaultPort);
        return new PropertiesEndpointGroup(classLoader, resourceName,
                                           endpointKeyPrefix, defaultPort, reloadable);
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

    private PropertiesEndpointGroup(List<Endpoint> endpoints) {
        setEndpoints(endpoints);
    }

    private PropertiesEndpointGroup(ClassLoader classLoader, String resourceName,
                                    String endpointKeyPrefix, int defaultPort, boolean reloadable) {
        final URL resourceUrl = getResourceUrl(
                requireNonNull(classLoader, "classLoader"),
                requireNonNull(resourceName, "fileName"));
        final List<Endpoint> endpoints = loadEndpoints(
                resourceUrl,
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                0);
        setEndpoints(endpoints);

        if (reloadable) {
            registry.register(resourceUrl, () -> {
                setEndpoints(loadEndpoints(resourceUrl, endpointKeyPrefix, defaultPort));
            });
            closeCallback = () -> registry.deregister(resourceUrl);
        }
    }

    private static URL getResourceUrl(ClassLoader classLoader, String resourceName) {
        final URL resourceUrl = classLoader.getResource(resourceName);
        checkArgument(resourceUrl != null, "resource not found: %s", resourceName);
        return resourceUrl;
    }

    private static List<Endpoint> loadEndpoints(URL resourceUrl, String endpointKeyPrefix, int defaultPort) {

        if (!endpointKeyPrefix.endsWith(".")) {
            endpointKeyPrefix += ".";
        }

        try (InputStream in = resourceUrl.openStream()) {
            final Properties props = new Properties();
            props.load(in);
            return loadEndpoints(props, endpointKeyPrefix, defaultPort);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to load: " + resourceUrl.getFile(), e);
        }
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

    private static void validateDefaultPort(int defaultPort) {
        checkArgument(defaultPort > 0 && defaultPort <= 65535,
                      "defaultPort: %s (expected: 1-65535)", defaultPort);
    }

    @Override
    public void close() {
        super.close();
        if (closeCallback != null) {
            closeCallback.run();
        }
    }
}

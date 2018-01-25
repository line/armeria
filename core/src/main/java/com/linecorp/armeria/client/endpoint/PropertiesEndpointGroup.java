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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

/**
 * A {@link Properties} backed {@link EndpointGroup}. The list of {@link Endpoint}s are loaded from the
 * {@link Properties}.
 * TODO(ide) Reload the endpoint list if the file is updated.
 */
public final class PropertiesEndpointGroup implements EndpointGroup {
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
     * @param resourceName the resource file name that the list of {@link Endpoint}s loaded from
     * @throws IllegalArgumentException if failed to load any hosts from the specified resource file
     */
    public static PropertiesEndpointGroup of(ClassLoader classLoader, String resourceName,
                                             String endpointKeyPrefix, int defaultPort) {
        checkArgument(defaultPort >= 0 && defaultPort <= 65535,
                      "defaultPort(%s) must be between 0 and 65535", defaultPort);
        return new PropertiesEndpointGroup(loadEndpoints(
                requireNonNull(classLoader, "classLoader"),
                requireNonNull(resourceName, "resourceName"),
                requireNonNull(endpointKeyPrefix, "endpointKeyPrefix"),
                defaultPort));
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
     * @param properties the {@link Properties} that the list of {@link Endpoint}s loaded from
     * @throws IllegalArgumentException if failed to load any hosts from the specified {@link Properties}
     */
    public static PropertiesEndpointGroup of(Properties properties, String endpointKeyPrefix,
                                             int defaultPort) {
        return new PropertiesEndpointGroup(loadEndpoints(properties, endpointKeyPrefix, defaultPort));
    }

    private static List<Endpoint> loadEndpoints(ClassLoader classLoader, String resourceName,
                                                String endpointKeyPrefix, int defaultPort) {
        final URL resourceUrl = classLoader.getResource(resourceName);
        if (resourceUrl == null) {
            throw new IllegalArgumentException(resourceName + " not found");
        }
        if (endpointKeyPrefix.endsWith(".")) {
            endpointKeyPrefix += ".";
        }
        try (InputStream in = resourceUrl.openStream()) {
            final Properties props = new Properties();
            props.load(in);
            return loadEndpoints(props, endpointKeyPrefix, defaultPort);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to load: " + resourceName, e);
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
                           "%s contains an endpoint group which is not allowed: %s", properties, value);
                newEndpoints.add(endpoint.withDefaultPort(defaultPort));
            }
        }
        checkArgument(!newEndpoints.isEmpty(), "%s contains no hosts.", properties);
        return ImmutableList.copyOf(newEndpoints);
    }

    private final List<Endpoint> endpoints;

    private PropertiesEndpointGroup(List<Endpoint> endpoints) {
        this.endpoints = endpoints;
    }

    @Override
    public List<Endpoint> endpoints() {
        return endpoints;
    }
}

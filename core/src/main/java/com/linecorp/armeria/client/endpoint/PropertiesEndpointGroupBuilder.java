/*
 * Copyright 2020 LINE Corporation
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
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Builds a {@link PropertiesEndpointGroup}.
 */
public final class PropertiesEndpointGroupBuilder {

    @Nullable
    private final URL resourceUrl;
    @Nullable
    private final Properties properties;
    @Nullable
    private final Path path;
    private final String endpointKeyPrefix;
    private int defaultPort;
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    PropertiesEndpointGroupBuilder(ClassLoader classLoader, String resourceName,
                                   String endpointKeyPrefix) {

        final URL resourceUrl = classLoader.getResource(resourceName);
        checkArgument(resourceUrl != null, "resource not found: %s", resourceName);

        this.resourceUrl = resourceUrl;
        this.endpointKeyPrefix = endpointKeyPrefix;

        properties = null;
        path = null;
    }

    PropertiesEndpointGroupBuilder(Properties properties, String endpointKeyPrefix) {
        this.properties = properties;
        this.endpointKeyPrefix = endpointKeyPrefix;

        resourceUrl = null;
        path = null;
    }

    PropertiesEndpointGroupBuilder(Path path, String endpointKeyPrefix) {
        this.path = path;
        this.endpointKeyPrefix = endpointKeyPrefix;

        resourceUrl = null;
        properties = null;
    }

    /**
     * Sets the default port number which is used when parsing an {@link Endpoint} without a port number.
     */
    public PropertiesEndpointGroupBuilder defaultPort(int defaultPort) {
        checkArgument(defaultPort > 0 && defaultPort <= 65535,
                      "defaultPort: %s (expected: 1-65535)", defaultPort);
        this.defaultPort = defaultPort;
        return this;
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link PropertiesEndpointGroup} being built.
     */
    public PropertiesEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    /**
     * Returns a new {@link PropertiesEndpointGroup} built from the properties set so far.
     */
    public PropertiesEndpointGroup build() {
        if (resourceUrl != null) {
            return new PropertiesEndpointGroup(selectionStrategy,
                                               loadEndpoints(resourceUrl, endpointKeyPrefix, defaultPort));
        }

        if (properties != null) {
            return new PropertiesEndpointGroup(selectionStrategy,
                                               loadEndpoints(properties, endpointKeyPrefix, defaultPort));
        }

        assert path != null;
        return new PropertiesEndpointGroup(selectionStrategy, path, endpointKeyPrefix, defaultPort);
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
}

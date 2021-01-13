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
package com.linecorp.armeria.server.consul;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.consul.ConsulConfigSetters;
import com.linecorp.armeria.internal.consul.ConsulClient;
import com.linecorp.armeria.internal.consul.ConsulClientBuilder;
import com.linecorp.armeria.server.Server;

/**
 * Builds a new {@link ConsulUpdatingListener}, which registers the server to Consul cluster.
 * <h3>Examples</h3>
 * <pre>{@code
 * ConsulUpdatingListener listener = ConsulUpdatingListener.builder(consulUri, "myService")
 *                                                         .build();
 * ServerBuilder sb = Server.builder();
 * sb.serverListener(listener);
 * }</pre>
 */
@UnstableApi
public final class ConsulUpdatingListenerBuilder implements ConsulConfigSetters {

    private static final long DEFAULT_CHECK_INTERVAL_MILLIS = 10_000;

    private final String serviceName;

    @Nullable
    private Endpoint serviceEndpoint;
    @Nullable
    private URI checkUri;
    private String checkInterval = DEFAULT_CHECK_INTERVAL_MILLIS + "ms";
    private HttpMethod checkMethod = HttpMethod.HEAD;
    private final ConsulClientBuilder consulClientBuilder;
    private final Set<String> tags = new HashSet<>();

    /**
     * Creates a {@link ConsulUpdatingListenerBuilder} with a service name.
     *
     * @param consulUri the URI of Consul API service
     * @param serviceName the service name to register
     */
    ConsulUpdatingListenerBuilder(URI consulUri, String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        checkArgument(!this.serviceName.isEmpty(), "serviceName can't be empty");
        consulClientBuilder = ConsulClient.builder(consulUri);
    }

    /**
     * Sets URI for checking health by Consul agent.
     *
     * @param checkUri the URI for checking health of service
     */
    public ConsulUpdatingListenerBuilder checkUri(URI checkUri) {
        this.checkUri = requireNonNull(checkUri, "checkUri");
        return this;
    }

    /**
     * Sets URI for checking health by Consul agent.
     *
     * @param checkUri the URI for checking health of service
     */
    public ConsulUpdatingListenerBuilder checkUri(String checkUri) {
        requireNonNull(checkUri, "checkUri");
        checkArgument(!checkUri.isEmpty(), "checkUri can't be empty");
        return checkUri(URI.create(checkUri));
    }

    /**
     * Sets HTTP method for checking health by Consul agent.
     * If not set {@code HttpMethod.HEAD} is used by default.
     *
     * <p>Note that the {@code checkMethod} should be configured with {@link #checkUri(String)}.
     * Otherwise, the {@link #build()} method will throw an {@link IllegalStateException}.
     *
     * @param checkMethod the {@link HttpMethod} for checking health of service
     */
    public ConsulUpdatingListenerBuilder checkMethod(HttpMethod checkMethod) {
        this.checkMethod = requireNonNull(checkMethod, "checkMethod");
        return this;
    }

    /**
     * Sets the specified {@link Duration} for checking health.
     * If not set {@value DEFAULT_CHECK_INTERVAL_MILLIS} milliseconds is used by default.
     *
     * <p>Note that the {@code checkInterval} should be configured with {@link #checkUri(URI)}.
     * Otherwise, the {@link #build()} method will throw an {@link IllegalStateException}.
     */
    public ConsulUpdatingListenerBuilder checkInterval(Duration checkInterval) {
        requireNonNull(checkInterval, "checkInterval");
        checkIntervalMillis(checkInterval.toMillis());
        return this;
    }

    /**
     * Sets the specified {@code checkIntervalMills} for checking health in milliseconds.
     * If not set {@value DEFAULT_CHECK_INTERVAL_MILLIS} is used by default.
     *
     * <p>Note that the {@code checkIntervalMillis} should be configured with {@link #checkUri(URI)}.
     * Otherwise, the {@link #build()} method will throws {@link IllegalStateException}.
     */
    public ConsulUpdatingListenerBuilder checkIntervalMillis(long checkIntervalMillis) {
        checkArgument(checkIntervalMillis > 0, "checkIntervalMillis should be positive");
        checkInterval = checkIntervalMillis + "ms";
        return this;
    }

    /**
     * Sets the {@link Endpoint} to register. If not set, the current host name is used by default.
     *
     * @param endpoint the {@link Endpoint} to register
     */
    public ConsulUpdatingListenerBuilder endpoint(Endpoint endpoint) {
        serviceEndpoint = requireNonNull(endpoint, "endpoint");
        return this;
    }

    /**
     * Adds a tag to the list of tags associated with the service on registration.
     *
     * @param tag the tag to add
     */
    public ConsulUpdatingListenerBuilder addTag(String tag) {
        tags.add(tag);
        return this;
    }

    /**
     * Adds a list of tags to the list of tags associated with the service on registration.
     *
     * @param tags the tags to add
     */
    public ConsulUpdatingListenerBuilder addTags(String... tags) {
        this.tags.addAll(Arrays.asList(tags));
        return this;
    }

    @Override
    public ConsulUpdatingListenerBuilder consulApiVersion(String consulApiVersion) {
        consulClientBuilder.consulApiVersion(consulApiVersion);
        return this;
    }

    @Override
    public ConsulUpdatingListenerBuilder consulToken(String consulToken) {
        consulClientBuilder.consulToken(consulToken);
        return this;
    }

    /**
     * Returns a newly-created {@link ConsulUpdatingListener} that registers the {@link Server} to
     * Consul when the {@link Server} starts.
     */
    public ConsulUpdatingListener build() {
        return new ConsulUpdatingListener(consulClientBuilder.build(), serviceName, serviceEndpoint,
                                          checkUri, checkMethod, checkInterval, tags);
    }
}

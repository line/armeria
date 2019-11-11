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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.internal.consul.ConsulClient;
import com.linecorp.armeria.server.Server;

/**
 * Builds a new {@link ConsulUpdatingListener}, which registers the server to Consul cluster.
 * <h3>Examples</h3>
 * <pre>{@code
 * ConsulUpdatingListener listener = ConsulUpdatingListener.builder("myService")
 *                                                         .consulUri("http://myConsulHost:8500")
 *                                                         .build();
 * Server.builder()
 *       .addListener(listener);
 * }</pre>
 */
public final class ConsulUpdatingListenerBuilder {

    private static final URI DEFAULT_CONSUL_URI = URI.create("http://127.0.0.1:8500/v1");
    private static final String DEFAULT_CHECK_INTERVAL = "10s";

    private final String serviceName;

    private URI consulUri = DEFAULT_CONSUL_URI;

    @Nullable
    private Endpoint serviceEndpoint;
    @Nullable
    private URI checkUri;
    @Nullable
    private String checkInterval;
    @Nullable
    private HttpMethod checkMethod;

    /**
     * Creates a {@link ConsulUpdatingListenerBuilder} with a service name.
     *
     * @param serviceName the service name to register
     */
    ConsulUpdatingListenerBuilder(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        checkArgument(!this.serviceName.isEmpty(), "serviceName can't be empty");
    }

    /**
     * Sets the specified Consul's URI.
     * If not set, {@code "http://127.0.0.1:8500/v1"} is used by default.
     *
     * @param consulUri the URI of Consul agent, e.g.: http://127.0.0.1:8500
     */
    public ConsulUpdatingListenerBuilder consulUri(URI consulUri) {
        this.consulUri = requireNonNull(consulUri, "consulUri");
        return this;
    }

    /**
     * Sets the specified Consul's URI.
     * If not set, {@code "http://127.0.0.1:8500/v1"} is used by default.
     *
     * @param consulUri the URI of Consul agent, e.g.: http://127.0.0.1:8500
     */
    public ConsulUpdatingListenerBuilder consulUri(String consulUri) {
        requireNonNull(consulUri, "consulUri");
        checkArgument(!consulUri.isEmpty(), "consulUri can't be empty");
        consulUri(URI.create(consulUri));
        return this;
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
     *
     * <p>Note that the {@code checkMethod} should be configured with {@link #checkUri(String)}.
     * Otherwise, the {@link #build()} method will throws {@link IllegalStateException}.
     *
     * @param checkMethod the {@link HttpMethod} for checking health of service
     */
    public ConsulUpdatingListenerBuilder checkMethod(HttpMethod checkMethod) {
        this.checkMethod = requireNonNull(checkMethod, "checkMethod");
        return this;
    }

    /**
     * Sets the specified {@link Duration} for checking health.
     * If not set {@value DEFAULT_CHECK_INTERVAL} is used by default.
     *
     * <p>Note that the {@code checkInterval} should be configured with {@link #checkUri(URI)}.
     * Otherwise, the {@link #build()} method will throws {@link IllegalStateException}.
     */
    public ConsulUpdatingListenerBuilder checkInterval(Duration checkInterval) {
        requireNonNull(checkInterval, "checkInterval");
        checkIntervalMillis(checkInterval.toMillis());
        return this;
    }

    /**
     * Sets the specified {@code checkIntervalMills} for checking health.
     * If not set {@value DEFAULT_CHECK_INTERVAL} is used by default.
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
     * Returns a newly-created {@link ConsulUpdatingListener} that registers the {@link Server} to
     * Consul when the {@link Server} starts.
     */
    public ConsulUpdatingListener build() {
        if (checkUri == null) {
            if (checkMethod != null) {
                throw new IllegalStateException("'checkMethod' should declare with checkUri.");
            }
            if (checkInterval != null) {
                throw new IllegalStateException("'checkInterval' should declare with checkUri.");
            }
        }

        final ConsulClient client = new ConsulClient(consulUri);
        return new ConsulUpdatingListener(client, serviceName, serviceEndpoint, checkUri, checkMethod,
                                          firstNonNull(checkInterval, DEFAULT_CHECK_INTERVAL));
    }
}

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
package com.linecorp.armeria.server.consul;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.consul.ConsulClient;

/**
 * Builds a new {@link ConsulUpdatingListener}, which registers the server to a Consul cluster.
 * <h2>Examples</h2>
 * <pre>{@code
 * ConsulUpdatingListener listener =
 *     new ConsulUpdatingListenerBuilder("myService").url("http://myConsulHost:8500").build();
 * ServerBuilder sb = Server.builder();
 * sb.addListener(listener);
 * }</pre>
 */
public final class ConsulUpdatingListenerBuilder {
    private final String serviceName;
    @Nullable
    private String consulUrl;
    @Nullable
    private Endpoint serviceEndpoint;

    @Nullable
    private String checkUrl;
    @Nullable
    private String checkMethod;
    @Nullable
    private String checkInterval;

    /**
     * Creates a {@link ConsulUpdatingListenerBuilder} with a service name.
     *
     * @param serviceName Service name to register
     */
    ConsulUpdatingListenerBuilder(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        checkArgument(!this.serviceName.isEmpty(), "serviceName can't be empty");
    }

    /**
     * Sets the Consul's URL.
     *
     * @param consulUrl URL of consul agent, e.g.: http://127.0.0.1:8500
     */
    public ConsulUpdatingListenerBuilder url(String consulUrl) {
        this.consulUrl = requireNonNull(consulUrl, "consulUrl");
        checkArgument(!this.consulUrl.isEmpty(), "consulUrl can't be empty");
        return this;
    }

    /**
     * Sets URL for checking health by consul agent.
     *
     * @param checkUrl URL for checking health of service
     */
    public ConsulUpdatingListenerBuilder checkUrl(String checkUrl) {
        this.checkUrl = requireNonNull(checkUrl, "checkUrl");
        return this;
    }

    /**
     * Sets HTTP method for checking health by consul agent.
     *
     * @param checkMethod HTTP method for checking health of service
     */
    public ConsulUpdatingListenerBuilder checkMethod(String checkMethod) {
        this.checkMethod = requireNonNull(checkMethod, "checkMethod");
        return this;
    }

    /**
     * Sets checkInterval in {@code java.time.Duration}.
     */
    public ConsulUpdatingListenerBuilder checkInterval(Duration checkInterval) {
        requireNonNull(checkInterval, "checkInterval");
        checkIntervalMillis(checkInterval.toMillis());
        return this;
    }

    /**
     * Sets checkInterval in milliseconds.
     * the interval will be converted to a {@code String} representation that Consul can recognize.
     * e.g.: "10s", "1m", "1000ms"
     */
    public ConsulUpdatingListenerBuilder checkIntervalMillis(long checkIntervalMillis) {
        checkArgument(checkIntervalMillis > 0, "checkIntervalMillis should be positive");
        checkInterval = checkIntervalMillis + "ms";
        return this;
    }

    /**
     * Sets the {@link Endpoint} to register. If not set, the current host name is used automatically.
     *
     * @param endpoint the {@link Endpoint} to register
     */
    public ConsulUpdatingListenerBuilder endpoint(Endpoint endpoint) {
        serviceEndpoint = requireNonNull(endpoint, "endpoint");
        return this;
    }

    /**
     * Returns a newly-created {@link ConsulUpdatingListener} instance that registers the server to
     * Consul when the server starts.
     */
    public ConsulUpdatingListener build() {
        if (checkUrl == null) {
            if (checkMethod != null) {
                throw new IllegalStateException("'checkMethod' should declare with checkUrl.");
            }
            if (checkInterval != null) {
                throw new IllegalStateException("'checkInterval' should declare with checkUrl.");
            }
        }
        final ConsulClient client = new ConsulClient(consulUrl);
        return new ConsulUpdatingListener(client, serviceName, serviceEndpoint, checkUrl, checkMethod,
                                          checkInterval);
    }
}

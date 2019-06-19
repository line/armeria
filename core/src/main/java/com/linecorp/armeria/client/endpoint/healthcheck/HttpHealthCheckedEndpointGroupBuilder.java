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

package com.linecorp.armeria.client.endpoint.healthcheck;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup.DEFAULT_HEALTHCHECK_RETRY_BACKOFF;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * A builder for creating new {@link HttpHealthCheckedEndpointGroup}s.
 */
public class HttpHealthCheckedEndpointGroupBuilder {

    private final EndpointGroup delegate;
    private final String healthCheckPath;

    private SessionProtocol protocol = SessionProtocol.HTTP;
    private Backoff retryBackoff = DEFAULT_HEALTHCHECK_RETRY_BACKOFF;
    private ClientFactory clientFactory = ClientFactory.DEFAULT;
    private Function<? super ClientOptionsBuilder, ClientOptionsBuilder> configurator = Function.identity();
    private int healthCheckPort;

    /**
     * Creates a new {@link HttpHealthCheckedEndpointGroupBuilder}. Health check requests for the delegate
     * {@link EndpointGroup} will be made against {@code healthCheckPath}.
     */
    public HttpHealthCheckedEndpointGroupBuilder(EndpointGroup delegate, String healthCheckPath) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.healthCheckPath = requireNonNull(healthCheckPath, "healthCheckPath");
    }

    /**
     * Sets the port where a health check request will be sent instead of the original port number
     * specified by {@link EndpointGroup}'s {@link Endpoint}s. This property is useful when your
     * server listens to health check requests on a different port.
     */
    public HttpHealthCheckedEndpointGroupBuilder healthCheckPort(int healthCheckPort) {
        checkArgument(healthCheckPort > 0 && healthCheckPort <= 65535,
                      "healthCheckPort: %s (expected: 1-65535)", healthCheckPort);
        this.healthCheckPort = healthCheckPort;
        return this;
    }

    /**
     * Sets the {@link SessionProtocol} to be used when making health check requests.
     */
    public HttpHealthCheckedEndpointGroupBuilder protocol(SessionProtocol protocol) {
        this.protocol = requireNonNull(protocol, "protocol");
        return this;
    }

    /**
     * Sets the interval between health check requests. Must be positive.
     * @deprecated Use {@link #retryBackoff(Backoff)}.
     */
    @Deprecated
    public HttpHealthCheckedEndpointGroupBuilder retryInterval(Duration retryInterval) {
        requireNonNull(retryInterval, "retryInterval");
        checkArgument(!retryInterval.isNegative() && !retryInterval.isZero(),
                      "retryInterval: %s (expected > 0)", retryInterval);
        retryBackoff = Backoff.fixed(retryInterval.toMillis())
                              .withJitter(0.2);
        return this;
    }

    /**
     * Sets the backoff between health check requests.
     */
    public HttpHealthCheckedEndpointGroupBuilder retryBackoff(Backoff retryBackoff) {
        this.retryBackoff = requireNonNull(retryBackoff, "retryBackoff");
        return this;
    }

    /**
     * Sets the {@link ClientFactory} to use when making health check requests. This should generally be the
     * same as the {@link ClientFactory} used when creating a {@link Client} stub using the
     * {@link EndpointGroup}.
     */
    public HttpHealthCheckedEndpointGroupBuilder clientFactory(ClientFactory clientFactory) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        return this;
    }

    /**
     * Sets the {@link Function} that customizes an {@link HttpClient} for health check.
     * <pre>{@code
     * new HttpHealthCheckedEndpointGroupBuilder(delegate, healthCheckPath)
     *     .withClientOptions(op -> op.responseTimeout(Duration.ofSeconds(1)))
     *     .build();
     * }</pre>
     */
    public HttpHealthCheckedEndpointGroupBuilder withClientOptions(
            Function<? super ClientOptionsBuilder, ClientOptionsBuilder> configurator) {
        this.configurator = requireNonNull(configurator, "configurator");
        return this;
    }

    /**
     * Returns a newly created {@link HttpHealthCheckedEndpointGroup} based on the contents of the
     * {@link HttpHealthCheckedEndpointGroupBuilder}.
     */
    public HttpHealthCheckedEndpointGroup build() {
        return new HttpHealthCheckedEndpointGroup(clientFactory, delegate, protocol, healthCheckPath,
                                                  healthCheckPort, retryBackoff, configurator);
    }
}

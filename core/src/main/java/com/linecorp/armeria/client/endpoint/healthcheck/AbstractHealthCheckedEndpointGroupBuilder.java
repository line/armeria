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

package com.linecorp.armeria.client.endpoint.healthcheck;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.AbstractDynamicEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.util.AsyncCloseable;

/**
 * A skeletal builder implementation for creating a new {@link HealthCheckedEndpointGroup}.
 */
public abstract class AbstractHealthCheckedEndpointGroupBuilder extends AbstractDynamicEndpointGroupBuilder {

    static final Backoff DEFAULT_HEALTH_CHECK_RETRY_BACKOFF = Backoff.fixed(3000).withJitter(0.2);

    private final EndpointGroup delegate;

    private SessionProtocol protocol = SessionProtocol.HTTP;
    private Backoff retryBackoff = DEFAULT_HEALTH_CHECK_RETRY_BACKOFF;
    private ClientOptionsBuilder clientOptionsBuilder = ClientOptions.builder();
    private int port;
    @Nullable
    private Double maxEndpointRatio;
    @Nullable
    private Integer maxEndpointCount;

    /**
     * Creates a new {@link AbstractHealthCheckedEndpointGroupBuilder}.
     *
     * @param delegate the {@link EndpointGroup} which provides the candidate {@link Endpoint}s
     */
    protected AbstractHealthCheckedEndpointGroupBuilder(EndpointGroup delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Sets the {@link ClientFactory} to use when making health check requests. This should generally be the
     * same as the {@link ClientFactory} used when creating a {@link Client} stub using the
     * {@link EndpointGroup}.
     */
    public AbstractHealthCheckedEndpointGroupBuilder clientFactory(ClientFactory clientFactory) {
        clientOptionsBuilder.factory(requireNonNull(clientFactory, "clientFactory"));
        return this;
    }

    /**
     * Sets the {@link SessionProtocol} to be used when making health check requests.
     */
    public AbstractHealthCheckedEndpointGroupBuilder protocol(SessionProtocol protocol) {
        this.protocol = requireNonNull(protocol, "protocol");
        return this;
    }

    /**
     * Sets the port where a health check request will be sent instead of the original port number
     * specified by {@link EndpointGroup}'s {@link Endpoint}s. This property is useful when your
     * server listens to health check requests on a different port.
     */
    public AbstractHealthCheckedEndpointGroupBuilder port(int port) {
        checkArgument(port > 0 && port <= 65535,
                      "port: %s (expected: 1-65535)", port);
        this.port = port;
        return this;
    }

    /**
     * Sets the interval between health check requests. Must be positive.
     */
    public AbstractHealthCheckedEndpointGroupBuilder retryInterval(Duration retryInterval) {
        requireNonNull(retryInterval, "retryInterval");
        checkArgument(!retryInterval.isNegative() && !retryInterval.isZero(),
                      "retryInterval: %s (expected: > 0)", retryInterval);
        return retryIntervalMillis(retryInterval.toMillis());
    }

    /**
     * Sets the interval between health check requests in milliseconds. Must be positive.
     */
    public AbstractHealthCheckedEndpointGroupBuilder retryIntervalMillis(long retryIntervalMillis) {
        checkArgument(retryIntervalMillis > 0,
                      "retryIntervalMillis: %s (expected: > 0)", retryIntervalMillis);
        return retryBackoff(Backoff.fixed(retryIntervalMillis).withJitter(0.2));
    }

    /**
     * Sets the backoff between health check requests.
     */
    public AbstractHealthCheckedEndpointGroupBuilder retryBackoff(Backoff retryBackoff) {
        this.retryBackoff = requireNonNull(retryBackoff, "retryBackoff");
        return this;
    }

    /**
     * Sets the {@link ClientOptions} of the {@link Client} that sends health check requests.
     * This method can be useful if you already have an Armeria client and want to reuse its configuration,
     * such as using the same decorators.
     * <pre>{@code
     * WebClient myClient = ...;
     * // Use the same settings and decorators with `myClient` when sending health check requests.
     * builder.clientOptions(myClient.options());
     * }</pre>
     */
    public AbstractHealthCheckedEndpointGroupBuilder clientOptions(ClientOptions clientOptions) {
        clientOptionsBuilder.options(requireNonNull(clientOptions, "clientOptions"));
        return this;
    }

    /**
     * Sets the {@link Function} that customizes a {@link Client} that sends health check requests.
     * <pre>{@code
     * builder.withClientOptions(b -> {
     *     return b.setHeader(HttpHeaders.AUTHORIZATION,
     *                        "bearer my-access-token")
     *             .responseTimeout(Duration.ofSeconds(3));
     * });
     * }</pre>
     */
    public AbstractHealthCheckedEndpointGroupBuilder withClientOptions(
            Function<? super ClientOptionsBuilder, ClientOptionsBuilder> configurator) {
        final ClientOptionsBuilder newBuilder =
                requireNonNull(configurator, "configurator").apply(clientOptionsBuilder);
        checkState(newBuilder != null, "configurator returned null.");
        clientOptionsBuilder = newBuilder;
        return this;
    }

    /**
     * Sets the maximum endpoint ratio of target selected candidates.
     */
    public AbstractHealthCheckedEndpointGroupBuilder maxEndpointRatio(double maxEndpointRatio) {
        if (maxEndpointCount != null) {
            throw new IllegalArgumentException("Maximum endpoint count is already set.");
        }

        checkArgument(maxEndpointRatio > 0 && maxEndpointRatio <= 1.0,
                      "maxEndpointRatio: %s (expected: 0.0 < maxEndpointRatio <= 1.0)",
                      maxEndpointRatio);

        this.maxEndpointRatio = maxEndpointRatio;
        return this;
    }

    /**
     * Sets the maximum endpoint count of target selected candidates.
     */
    public AbstractHealthCheckedEndpointGroupBuilder maxEndpointCount(int maxEndpointCount) {
        if (maxEndpointRatio != null) {
            throw new IllegalArgumentException("Maximum endpoint ratio is already set.");
        }

        checkArgument(maxEndpointCount > 0, "maxEndpointCount: %s (expected: > 0)", maxEndpointCount);

        this.maxEndpointCount = maxEndpointCount;
        return this;
    }

    /**
     * Sets the {@link AuthToken} header using {@link HttpHeaderNames#AUTHORIZATION}.
     */
    public AbstractHealthCheckedEndpointGroupBuilder auth(AuthToken token) {
        requireNonNull(token, "token");
        clientOptionsBuilder.auth(token);
        return this;
    }

    @Override
    public AbstractHealthCheckedEndpointGroupBuilder allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        return (AbstractHealthCheckedEndpointGroupBuilder) super.allowEmptyEndpoints(allowEmptyEndpoints);
    }

    /**
     * Returns a newly created {@link HealthCheckedEndpointGroup} based on the properties set so far.
     */
    public final HealthCheckedEndpointGroup build() {
        final HealthCheckStrategy healthCheckStrategy;
        if (maxEndpointCount != null) {
            healthCheckStrategy = HealthCheckStrategy.ofCount(maxEndpointCount);
        } else {
            if (maxEndpointRatio == null || maxEndpointRatio == 1.0) {
                healthCheckStrategy = HealthCheckStrategy.all();
            } else {
                healthCheckStrategy = HealthCheckStrategy.ofRatio(maxEndpointRatio);
            }
        }

        return new HealthCheckedEndpointGroup(delegate, shouldAllowEmptyEndpoints(),
                                              protocol, port, retryBackoff,
                                              clientOptionsBuilder.build(),
                                              newCheckerFactory(), healthCheckStrategy);
    }

    /**
     * Returns the {@link Function} that starts to send health check requests to the {@link Endpoint}
     * specified in a given {@link HealthCheckerContext} when invoked. The {@link Function} must update
     * the health of the {@link Endpoint} with a value between [0, 1] via
     * {@link HealthCheckerContext#updateHealth(double)}. {@link HealthCheckedEndpointGroup} will call
     * {@link AsyncCloseable#closeAsync()} on the {@link AsyncCloseable} returned by the {@link Function}
     * when it needs to stop sending health check requests.
     */
    protected abstract Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory();
}

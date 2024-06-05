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

import com.google.common.math.LongMath;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.AbstractDynamicEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.util.AsyncCloseable;

/**
 * A skeletal builder implementation for creating a new {@link HealthCheckedEndpointGroup}.
 */
public abstract class AbstractHealthCheckedEndpointGroupBuilder
        <SELF extends AbstractHealthCheckedEndpointGroupBuilder<SELF>>
        extends AbstractDynamicEndpointGroupBuilder<SELF> {

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

    private long initialSelectionTimeoutMillis = Flags.defaultResponseTimeoutMillis();
    private long selectionTimeoutMillis = Flags.defaultConnectTimeoutMillis();

    /**
     * Creates a new {@link AbstractHealthCheckedEndpointGroupBuilder}.
     *
     * @param delegate the {@link EndpointGroup} which provides the candidate {@link Endpoint}s
     */
    protected AbstractHealthCheckedEndpointGroupBuilder(EndpointGroup delegate) {
        super(Flags.defaultResponseTimeoutMillis());
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Sets the {@link ClientFactory} to use when making health check requests. This should generally be the
     * same as the {@link ClientFactory} used when creating a {@link Client} stub using the
     * {@link EndpointGroup}.
     */
    public SELF clientFactory(ClientFactory clientFactory) {
        clientOptionsBuilder.factory(requireNonNull(clientFactory, "clientFactory"));
        return self();
    }

    /**
     * Sets the {@link SessionProtocol} to be used when making health check requests.
     */
    public SELF protocol(SessionProtocol protocol) {
        this.protocol = requireNonNull(protocol, "protocol");
        return self();
    }

    /**
     * Sets the port where a health check request will be sent instead of the original port number
     * specified by {@link EndpointGroup}'s {@link Endpoint}s. This property is useful when your
     * server listens to health check requests on a different port.
     */
    public SELF port(int port) {
        checkArgument(port > 0 && port <= 65535,
                      "port: %s (expected: 1-65535)", port);
        this.port = port;
        return self();
    }

    /**
     * Sets the interval between health check requests. Must be positive.
     */
    public SELF retryInterval(Duration retryInterval) {
        requireNonNull(retryInterval, "retryInterval");
        checkArgument(!retryInterval.isNegative() && !retryInterval.isZero(),
                      "retryInterval: %s (expected: > 0)", retryInterval);
        return retryIntervalMillis(retryInterval.toMillis());
    }

    /**
     * Sets the interval between health check requests in milliseconds. Must be positive.
     */
    public SELF retryIntervalMillis(long retryIntervalMillis) {
        checkArgument(retryIntervalMillis > 0,
                      "retryIntervalMillis: %s (expected: > 0)", retryIntervalMillis);
        return retryBackoff(Backoff.fixed(retryIntervalMillis).withJitter(0.2));
    }

    /**
     * Sets the backoff between health check requests.
     */
    public SELF retryBackoff(Backoff retryBackoff) {
        this.retryBackoff = requireNonNull(retryBackoff, "retryBackoff");
        return self();
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
    public SELF clientOptions(ClientOptions clientOptions) {
        clientOptionsBuilder.options(requireNonNull(clientOptions, "clientOptions"));
        return self();
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
    public SELF withClientOptions(Function<? super ClientOptionsBuilder, ClientOptionsBuilder> configurator) {
        final ClientOptionsBuilder newBuilder =
                requireNonNull(configurator, "configurator").apply(clientOptionsBuilder);
        checkState(newBuilder != null, "configurator returned null.");
        clientOptionsBuilder = newBuilder;
        return self();
    }

    /**
     * Sets the maximum endpoint ratio of target selected candidates.
     */
    public SELF maxEndpointRatio(double maxEndpointRatio) {
        if (maxEndpointCount != null) {
            throw new IllegalArgumentException("Maximum endpoint count is already set.");
        }

        checkArgument(maxEndpointRatio > 0 && maxEndpointRatio <= 1.0,
                      "maxEndpointRatio: %s (expected: 0.0 < maxEndpointRatio <= 1.0)",
                      maxEndpointRatio);

        this.maxEndpointRatio = maxEndpointRatio;
        return self();
    }

    /**
     * Sets the maximum endpoint count of target selected candidates.
     */
    public SELF maxEndpointCount(int maxEndpointCount) {
        if (maxEndpointRatio != null) {
            throw new IllegalArgumentException("Maximum endpoint ratio is already set.");
        }

        checkArgument(maxEndpointCount > 0, "maxEndpointCount: %s (expected: > 0)", maxEndpointCount);

        this.maxEndpointCount = maxEndpointCount;
        return self();
    }

    /**
     * Sets the {@link AuthToken} header using {@link HttpHeaderNames#AUTHORIZATION}.
     */
    public SELF auth(AuthToken token) {
        requireNonNull(token, "token");
        clientOptionsBuilder.auth(token);
        return self();
    }

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * This method is shortcut for {@code selectionTimeout(selectionTimeout, selectionTimeout)}.
     * {@link Duration#ZERO} disables the timeout.
     * If unspecified, {@link Flags#defaultResponseTimeoutMillis()} is used to wait for the
     * {@linkplain HealthCheckedEndpointGroup#whenReady() initial endpoints}, and
     * {@link Flags#defaultConnectTimeoutMillis()}} is used after the initial endpoints are resolved by default.
     *
     * <p>Note that the specified {@code selectionTimeout} and the delegate's
     * {@linkplain EndpointGroup#selectionTimeoutMillis() selectionTimeout} are added and set to
     * this {@link HealthCheckedEndpointGroup}.
     * For example:<pre>{@code
     * DnsAddressEndpointGroup delegate = DnsAddressEndpointGroup.builder("armeria.dev")
     *                                                           .selectionTimeout(Duration.ofSeconds(3))
     *                                                           ...
     *                                                           .build();
     * HealthCheckedEndpointGroup endpointGroup =
     *     HealthCheckedEndpointGroup.builder(delegate, "/health")
     *                               // Sets the same timeout to both initialSelectionTimeout
     *                               // and selectionTimeout
     *                               .selectionTimeout(Duration.ofSeconds(10))
     *                               ...
     *                               .build();
     * // The selection timeout of `delegate` is added into `endpointGroup`.
     * assert endpointGroup.selectionTimeoutMillis() == 13000; // 10000 (health) + 3000 (dns)
     * endpointGroup.whenReady.join();
     * // The selection timeout won't be changed even after the endpoint initialization.
     * assert endpointGroup.selectionTimeoutMillis() == 13000; // 10000 (health) + 3000 (dns)
     * }</pre>
     */
    @UnstableApi
    @Override
    public SELF selectionTimeout(Duration selectionTimeout) {
        return selectionTimeout(selectionTimeout, selectionTimeout);
    }

    /**
     * Sets the timeouts to wait until a successful {@link Endpoint} selection.
     * {@link Duration#ZERO} disables the timeout.
     *
     * <p>The final timeout is calculated by adding the
     * {@link HealthCheckedEndpointGroup#selectionTimeoutMillis()} and the selection timeout of the delegate
     * specified when creating this builder.
     * For example:<pre>{@code
     * DnsAddressEndpointGroup delegate = DnsAddressEndpointGroup.builder("armeria.dev")
     *                                                           .selectionTimeout(Duration.ofSeconds(3))
     *                                                           ...
     *                                                           .build();
     * HealthCheckedEndpointGroup endpointGroup =
     *     HealthCheckedEndpointGroup.builder(delegate, "/health")
     *                               .selectionTimeout(Duration.ofSeconds(10), Duration.ofSeconds(5))
     *                               ...
     *                               .build();
     * assert endpointGroup.selectionTimeoutMillis() == 13000; // 10000 (health) + 3000 (dns)
     *
     * // Wait for the initial endpoints.
     * endpointGroup.whenReady().join();
     * assert endpointGroup.selectionTimeoutMillis() == 8000; // 5000 (health) + 3000 (dns)
     * }</pre>
     *
     * @param initialSelectionTimeout the initial selection timeout to wait for the
     *                                {@linkplain HealthCheckedEndpointGroup#whenReady() initial endpoints}.
     *                                If unspecified, {@link Flags#defaultResponseTimeoutMillis()} is used by
     *                                default.
     * @param selectionTimeout the selection timeout to wait for an {@link Endpoint} after the initial
     *                         endpoints are resolved.
     *                         If unspecified, {@link Flags#defaultConnectTimeoutMillis()}} is used by
     *                         default.
     */
    @UnstableApi
    public SELF selectionTimeout(Duration initialSelectionTimeout, Duration selectionTimeout) {
        requireNonNull(initialSelectionTimeout, "initialSelectionTimeout");
        requireNonNull(selectionTimeout, "selectionTimeout");
        return selectionTimeoutMillis(initialSelectionTimeout.toMillis(), selectionTimeout.toMillis());
    }

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * This method is shortcut for {@code selectionTimeoutMillis(selectionTimeout, selectionTimeout)}.
     * {@link Duration#ZERO} disables the timeout.
     * If unspecified, {@link Flags#defaultResponseTimeoutMillis()} is used to wait for the
     * {@linkplain HealthCheckedEndpointGroup#whenReady() initial endpoints}, and
     * {@link Flags#defaultConnectTimeoutMillis()}} is used after the initial endpoints are resolved by default.
     *
     * <p>Note that the specified {@code selectionTimeoutMillis} and the delegate's
     * {@linkplain EndpointGroup#selectionTimeoutMillis() selectionTimeoutMillis} are added and set to
     * this {@link HealthCheckedEndpointGroup}.
     * For example:<pre>{@code
     * DnsAddressEndpointGroup delegate = DnsAddressEndpointGroup.builder("armeria.dev")
     *                                                           .selectionTimeoutMillis(3000)
     *                                                           ...
     *                                                           .build();
     * HealthCheckedEndpointGroup endpointGroup =
     *     HealthCheckedEndpointGroup.builder(delegate, "/health")
     *                               // Sets the same timeout to both initialSelectionTimeoutMills
     *                               // and selectionTimeoutMillis
     *                               .selectionTimeoutMillis(10000)
     *                               ...
     *                               .build();
     *
     * // The selection timeout of `delegate` is added into `endpointGroup`.
     * assert endpointGroup.selectionTimeoutMillis() == 13000; // 10000 (health) + 3000 (dns)
     * endpointGroup.whenReady.join();
     * // The selection timeout won't be changed even after the endpoint initialization.
     * assert endpointGroup.selectionTimeoutMillis() == 13000; // 10000 (health) + 3000 (dns)
     * }</pre>
     */
    @UnstableApi
    @Override
    public SELF selectionTimeoutMillis(long selectionTimeoutMillis) {
        return selectionTimeoutMillis(selectionTimeoutMillis, selectionTimeoutMillis);
    }

    /**
     * Sets the timeouts to wait until a successful {@link Endpoint} selection.
     * {@code 0} disables the timeout.
     *
     * <p>The final timeout is calculated by adding the
     * {@link HealthCheckedEndpointGroup#selectionTimeoutMillis()} and the selection timeout of the delegate
     * specified when creating this builder.
     * For example:<pre>{@code
     * DnsAddressEndpointGroup delegate = DnsAddressEndpointGroup.builder("armeria.dev")
     *                                                           .selectionTimeoutMillis(3000)
     *                                                           ...
     *                                                           .build();
     * HealthCheckedEndpointGroup endpointGroup =
     *     HealthCheckedEndpointGroup.builder(delegate, "/health")
     *                               .selectionTimeoutMillis(10000, 5000)
     *                               ...
     *                               .build();
     * assert endpointGroup.selectionTimeoutMillis() == 13000; // 10000 (health) + 3000 (dns)
     *
     * // Wait for the initial endpoints.
     * endpointGroup.whenReady().join();
     * assert endpointGroup.selectionTimeoutMillis() == 8000;  // 5000 (health) + 3000 (dns)
     * }</pre>
     *
     * @param initialSelectionTimeoutMillis the initial selection timeout to wait for the
     *                                {@linkplain HealthCheckedEndpointGroup#whenReady() initial endpoints}.
     *                                If unspecified, {@link Flags#defaultResponseTimeoutMillis()} is used by
     *                                default.
     * @param selectionTimeoutMillis the selection timeout to wait for an {@link Endpoint} after the initial
     *                         endpoints are resolved.
     *                         If unspecified, {@link Flags#defaultConnectTimeoutMillis()}} is used by
     *                         default.
     */
    @UnstableApi
    public SELF selectionTimeoutMillis(long initialSelectionTimeoutMillis, long selectionTimeoutMillis) {
        checkArgument(selectionTimeoutMillis >= 0, "selectionTimeoutMillis: %s (expected: >= 0)",
                      selectionTimeoutMillis);
        checkArgument(initialSelectionTimeoutMillis >= 0, "initialSelectionTimeoutMillis: %s (expected: >= 0)",
                      initialSelectionTimeoutMillis);
        if (initialSelectionTimeoutMillis == 0) {
            initialSelectionTimeoutMillis = Long.MAX_VALUE;
        }
        if (selectionTimeoutMillis == 0) {
            selectionTimeoutMillis = Long.MAX_VALUE;
        }
        this.initialSelectionTimeoutMillis = initialSelectionTimeoutMillis;
        this.selectionTimeoutMillis = selectionTimeoutMillis;
        return self();
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

        final long initialSelectionTimeoutMillis =
                LongMath.saturatedAdd(this.initialSelectionTimeoutMillis, delegate.selectionTimeoutMillis());
        final long selectionTimeoutMillis =
                LongMath.saturatedAdd(this.selectionTimeoutMillis, delegate.selectionTimeoutMillis());

        return new HealthCheckedEndpointGroup(delegate, shouldAllowEmptyEndpoints(),
                                              initialSelectionTimeoutMillis, selectionTimeoutMillis,
                                              protocol, port, retryBackoff,
                                              clientOptionsBuilder.build(),
                                              newCheckerFactory(), healthCheckStrategy);
    }

    /**
     * Returns the {@link Function} that starts to send health check requests to the {@link Endpoint}
     * specified in a given {@link HealthCheckerContext} when invoked. The {@link Function} must update
     * the health of the {@link Endpoint} with a value between [0, 1] via
     * {@link HealthCheckerContext#updateHealth(double, ClientRequestContext, ResponseHeaders, Throwable)}.
     * {@link HealthCheckedEndpointGroup} will call {@link AsyncCloseable#closeAsync()} on the
     * {@link AsyncCloseable} returned by the {@link Function} when it needs to stop sending health check
     * requests.
     */
    protected abstract Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory();
}

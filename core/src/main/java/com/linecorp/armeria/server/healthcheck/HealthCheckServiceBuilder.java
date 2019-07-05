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
package com.linecorp.armeria.server.healthcheck;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.auth.HttpAuthService;

/**
 * Builds a {@link HealthCheckService}.
 */
public final class HealthCheckServiceBuilder {

    private static final int DEFAULT_LONG_POLLING_TIMEOUT_SECONDS = 60;
    private static final double DEFAULT_LONG_POLLING_TIMEOUT_JITTER_RATE = 0.2;

    private final ImmutableSet.Builder<HealthChecker> healthCheckers = ImmutableSet.builder();
    private AggregatedHttpResponse healthyResponse = AggregatedHttpResponse.of(HttpStatus.OK,
                                                                               MediaType.JSON_UTF_8,
                                                                               "{\"healthy\":true}");
    private AggregatedHttpResponse unhealthyResponse = AggregatedHttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE,
                                                                                 MediaType.JSON_UTF_8,
                                                                                 "{\"healthy\":false}");
    private long maxLongPollingTimeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_LONG_POLLING_TIMEOUT_SECONDS);
    private double longPollingTimeoutJitterRate = DEFAULT_LONG_POLLING_TIMEOUT_JITTER_RATE;
    @Nullable
    private HealthCheckUpdateHandler updateHandler;

    HealthCheckServiceBuilder() {}

    /**
     * Adds the specified {@link HealthChecker}s that determine the healthiness of the {@link Server}.
     *
     * @return {@code this}
     */
    public HealthCheckServiceBuilder checkers(HealthChecker... healthCheckers) {
        return checkers(ImmutableSet.copyOf(requireNonNull(healthCheckers, "healthCheckers")));
    }

    /**
     * Adds the specified {@link HealthChecker}s that determine the healthiness of the {@link Server}.
     *
     * @return {@code this}
     */
    public HealthCheckServiceBuilder checkers(Iterable<? extends HealthChecker> healthCheckers) {
        this.healthCheckers.addAll(requireNonNull(healthCheckers, "healthCheckers"));
        return this;
    }

    /**
     * Sets the {@link AggregatedHttpResponse} to send when the {@link Service} is healthy. The following
     * response is sent by default:
     *
     * <pre>{@code
     * HTTP/1.1 200 OK
     * Content-Type: application/json; charset=utf-8
     *
     * { "healthy": true }
     * }</pre>
     *
     * @return {@code this}
     */
    public HealthCheckServiceBuilder healthyResponse(AggregatedHttpResponse healthyResponse) {
        requireNonNull(healthyResponse, "healthyResponse");
        this.healthyResponse = copyResponse(healthyResponse);
        return this;
    }

    /**
     * Sets the {@link AggregatedHttpResponse} to send when the {@link Service} is unhealthy. The following
     * response is sent by default:
     *
     * <pre>{@code
     * HTTP/1.1 503 Service Unavailable
     * Content-Type: application/json; charset=utf-8
     *
     * { "healthy": false }
     * }</pre>
     *
     * @return {@code this}
     */
    public HealthCheckServiceBuilder unhealthyResponse(AggregatedHttpResponse unhealthyResponse) {
        requireNonNull(unhealthyResponse, "unhealthyResponse");
        this.unhealthyResponse = copyResponse(unhealthyResponse);
        return this;
    }

    /**
     * Make a copy just in case the content is modified by the caller or is backed by ByteBuf.
     */
    private static AggregatedHttpResponse copyResponse(AggregatedHttpResponse res) {
        return AggregatedHttpResponse.of(res.informationals(),
                                         res.headers(),
                                         HttpData.copyOf(res.content().array()),
                                         res.trailers());
    }

    /**
     * Enables or disables long-polling support. By default, long-polling support is enabled with
     * the max timeout of {@value #DEFAULT_LONG_POLLING_TIMEOUT_SECONDS} seconds and
     * the jitter rate of {@value #DEFAULT_LONG_POLLING_TIMEOUT_JITTER_RATE}.
     *
     * @param maxLongPollingTimeout A positive maximum allowed timeout value which is specified by a client
     *                              in the {@code "prefer: wait=<n>"} request header.
     *                              Specify {@code 0} to disable long-polling support.
     * @return {@code this}
     * @see #longPolling(Duration, double)
     */
    public HealthCheckServiceBuilder longPolling(Duration maxLongPollingTimeout) {
        return longPolling(maxLongPollingTimeout, longPollingTimeoutJitterRate);
    }

    /**
     * Enables or disables long-polling support. By default, long-polling support is enabled with
     * the max timeout of {@value #DEFAULT_LONG_POLLING_TIMEOUT_SECONDS} seconds and
     * the jitter rate of {@value #DEFAULT_LONG_POLLING_TIMEOUT_JITTER_RATE}.
     *
     * @param maxLongPollingTimeoutMillis A positive maximum allowed timeout value which is specified by
     *                                    a client in the {@code "prefer: wait=<n>"} request header.
     *                                    Specify {@code 0} to disable long-polling support.
     * @return {@code this}
     * @see #longPolling(long, double)
     */
    public HealthCheckServiceBuilder longPolling(long maxLongPollingTimeoutMillis) {
        return longPolling(maxLongPollingTimeoutMillis, longPollingTimeoutJitterRate);
    }

    /**
     * Enables or disables long-polling support. By default, long-polling support is enabled with
     * the max timeout of {@value #DEFAULT_LONG_POLLING_TIMEOUT_SECONDS} seconds and
     * the jitter rate of {@value #DEFAULT_LONG_POLLING_TIMEOUT_JITTER_RATE}.
     *
     * @param maxLongPollingTimeout A positive maximum allowed timeout value which is specified by a client
     *                              in the {@code "prefer: wait=<n>"} request header.
     *                              Specify {@code 0} to disable long-polling support.
     * @param longPollingTimeoutJitterRate The jitter rate which adds a random variation to the long-polling
     *                                     timeout specified in the {@code "prefer: wait=<n>"} header.
     * @return {@code this}
     * @see #longPolling(Duration)
     */
    public HealthCheckServiceBuilder longPolling(Duration maxLongPollingTimeout,
                                                 double longPollingTimeoutJitterRate) {
        requireNonNull(maxLongPollingTimeout, "maxLongPollingTimeout");
        checkArgument(!maxLongPollingTimeout.isNegative(),
                      "maxLongPollingTimeout: %s (expected: >= 0)", maxLongPollingTimeout);
        return longPolling(maxLongPollingTimeout.toMillis(), longPollingTimeoutJitterRate);
    }

    /**
     * Enables or disables long-polling support. By default, long-polling support is enabled with
     * the max timeout of {@value #DEFAULT_LONG_POLLING_TIMEOUT_SECONDS} seconds and
     * the jitter rate of {@value #DEFAULT_LONG_POLLING_TIMEOUT_JITTER_RATE}.
     *
     * @param maxLongPollingTimeoutMillis A positive maximum allowed timeout value which is specified by
     *                                    a client in the {@code "prefer: wait=<n>"} request header.
     *                                    Specify {@code 0} to disable long-polling support.
     * @param longPollingTimeoutJitterRate The jitter rate which adds a random variation to the long-polling
     *                                     timeout specified in the {@code "prefer: wait=<n>"} header.
     * @return {@code this}
     * @see #longPolling(long)
     */
    public HealthCheckServiceBuilder longPolling(long maxLongPollingTimeoutMillis,
                                                 double longPollingTimeoutJitterRate) {
        checkArgument(maxLongPollingTimeoutMillis >= 0,
                      "maxLongPollingTimeoutMillis: %s (expected: >= 0)",
                      maxLongPollingTimeoutMillis);
        checkArgument(longPollingTimeoutJitterRate >= 0 && longPollingTimeoutJitterRate <= 1,
                      "longPollingTimeoutJitterRate: %s (expected: >= 0 && <= 1)",
                      longPollingTimeoutJitterRate);
        this.maxLongPollingTimeoutMillis = maxLongPollingTimeoutMillis;
        this.longPollingTimeoutJitterRate = longPollingTimeoutJitterRate;
        return this;
    }

    /**
     * Specifies whether the healthiness of the {@link Server} can be updated by sending a {@code PUT},
     * {@code POST} or {@code PATCH} request to the {@link HealthCheckService}. This feature is disabled
     * by default. If enabled, a JSON object which has a boolean property named {@code "healthy"} can be
     * sent using a {@code PUT} or {@code POST} request. A JSON patch in a {@code PATCH} request is also
     * accepted. It is recommended to employ some authorization mechanism such as {@link HttpAuthService}
     * when enabling this feature.
     *
     * @return {@code this}
     * @see #updatable(HealthCheckUpdateHandler)
     */
    public HealthCheckServiceBuilder updatable(boolean updatable) {
        if (updatable) {
            return updatable(DefaultHealthCheckUpdateHandler.INSTANCE);
        }

        updateHandler = null;
        return this;
    }

    /**
     * Specifies a {@link HealthCheckUpdateHandler} which handles other HTTP methods than {@code HEAD} and
     * {@code GET} which updates the healthiness of the {@link Server}. This feature is disabled by default.
     * It is recommended to employ some authorization mechanism such as {@link HttpAuthService}
     * when enabling this feature.
     *
     * @param updateHandler The {@link HealthCheckUpdateHandler} which handles {@code PUT}, {@code POST} or
     *                      {@code PATCH} requests and tells if the {@link Server} needs to be marked as
     *                      healthy or unhealthy.
     * @see #updatable(boolean)
     */
    public HealthCheckServiceBuilder updatable(HealthCheckUpdateHandler updateHandler) {
        this.updateHandler = requireNonNull(updateHandler, "updateHandler");
        return this;
    }

    /**
     * Returns a newly created {@link HealthCheckService} built from the properties specified so far.
     */
    public HealthCheckService build() {
        return new HealthCheckService(healthCheckers.build(),
                                      healthyResponse, unhealthyResponse,
                                      maxLongPollingTimeoutMillis, longPollingTimeoutJitterRate,
                                      updateHandler);
    }
}

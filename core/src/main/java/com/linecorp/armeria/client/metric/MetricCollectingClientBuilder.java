/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.client.metric;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

/**
 * Builds a {@link MetricCollectingClient} instance.
 */
public final class MetricCollectingClientBuilder {

    private final MeterIdPrefixFunction meterIdPrefixFunction;

    @Nullable
    private Predicate<? super RequestLog> successFunction;

    MetricCollectingClientBuilder(MeterIdPrefixFunction meterIdPrefixFunction) {
        this.meterIdPrefixFunction = meterIdPrefixFunction;
    }

    /**
     * Defines a custom {@link Predicate} to allow custom definition of successful responses.
     * In other words, specify which responses should increment metrics.success() and which - metrics.failure()
     *
     * <p>Example:
     * <pre>{@code
     *  MetricCollectingClient
     *    .builder()
     *    .successFunction(log -> {
     *      final int statusCode = log.responseHeaders().status().code();
     *      return (statusCode >= 200 && statusCode < 400) || statusCode == 404;
     *     })
     *    .newDecorator(MeterIdPrefixFunction.ofDefault("hello")));
     * }
     * </pre>
     */
    public MetricCollectingClientBuilder successFunction(Predicate<? super RequestLog> successFunction) {
        this.successFunction = successFunction;
        return this;
    }

    /**
     * Returns a newly-created {@link MetricCollectingClient} decorating {@link HttpClient} based
     * on the properties of this builder.
     */
    public MetricCollectingClient build(HttpClient delegate) {
        requireNonNull(delegate, "delegate");
        return new MetricCollectingClient(delegate, meterIdPrefixFunction, successFunction);
    }

    /**
     * Returns a newly-created {@link MetricCollectingClient} decorator based
     * on the properties of this builder and applies {@link MeterIdPrefixFunction}.
     */
    public Function<? super HttpClient, MetricCollectingClient> newDecorator() {
        return this::build;
    }
}

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
package com.linecorp.armeria.common.metric;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.client.metric.MetricCollectingRpcClient;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.metric.MetricCollectingService;

/**
 * Builds an implementing class of {@link AbstractMetricCollectingBuilder} instance.
 * Currently the generic types match:
 * <ul>
 *     <li>R = {@link MetricCollectingClient} and T = {@link HttpClient}</li>
 *     <li>R = {@link MetricCollectingRpcClient} and T = {@link RpcClient}</li>
 *     <li>R = {@link MetricCollectingService} and T = {@link HttpService}</li>
 * </ul>
 */
public abstract class AbstractMetricCollectingBuilder<R, T> {

    private final MeterIdPrefixFunction meterIdPrefixFunction;

    @Nullable
    private Predicate<? super RequestLog> successFunction;

    protected AbstractMetricCollectingBuilder(MeterIdPrefixFunction meterIdPrefixFunction) {
        this.meterIdPrefixFunction = requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
    }

    /**
     * Defines a custom {@link Predicate} to allow custom definition of successful responses.
     * In other words, specify which responses should increment metrics.success() and which - metrics.failure()
     *
     * <p>Example:
     * <pre>{@code
     *     MetricCollectingService
     *         .builder()
     *         .successFunction(log -> {
     *             final int statusCode = log.responseHeaders().status().code();
     *             return (statusCode >= 200 && statusCode < 400) || statusCode == 404;
     *         })
     *         .newDecorator(MeterIdPrefixFunction.ofDefault("hello")));
     * }</pre>
     */
    public AbstractMetricCollectingBuilder<R, T> successFunction(
            Predicate<? super RequestLog> successFunction) {
        this.successFunction = successFunction;
        return this;
    }

    /**
     * Getter for {@link meterIdPrefixFunction}.
     */
    public MeterIdPrefixFunction getMeterIdPrefixFunction() {
        return meterIdPrefixFunction;
    }

    /**
     * Getter for {@link successFunction}.
     */
    @Nullable
    public Predicate<? super RequestLog> getSuccessFunction() {
        return successFunction;
    }

    /**
     * Returns a newly-created {@link R} decorating {@link T} based on the properties of this builder.
     * @see AbstractMetricCollectingBuilder for supported types.
     */
    public abstract R build(T delegate);

    /**
     * Returns a newly-created {@link R} decorating {@link T} based on the properties of this builder,
     * and applies {@link MeterIdPrefixFunction}.
     * @see AbstractMetricCollectingBuilder for supported types.
     */
    public abstract Function<? super T, R> newDecorator();
}

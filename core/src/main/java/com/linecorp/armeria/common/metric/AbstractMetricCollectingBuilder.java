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

import java.util.function.BiPredicate;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * Builds an implementing class of {@link AbstractMetricCollectingBuilder} instance.
 */
public abstract class AbstractMetricCollectingBuilder {

    private final MeterIdPrefixFunction meterIdPrefixFunction;

    @Nullable
    private BiPredicate<? super RequestContext, ? super RequestLog> successFunction;

    /**
     * Creates a new instance with the specified {@link MeterIdPrefixFunction}.
     */
    protected AbstractMetricCollectingBuilder(MeterIdPrefixFunction meterIdPrefixFunction) {
        this.meterIdPrefixFunction = requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
    }

    /**
     * Returns the {@link MeterIdPrefixFunction}.
     */
    protected MeterIdPrefixFunction meterIdPrefixFunction() {
        return meterIdPrefixFunction;
    }

    /**
     * Returns the {@code successFunction}.
     */
    @Nullable
    protected final BiPredicate<? super RequestContext, ? super RequestLog> successFunction() {
        return successFunction;
    }

    /**
     * Defines a custom {@link BiPredicate} to allow custom definition of successful responses.
     * In other words, specify which responses should increment {@code metrics.success()}
     * and which - {@code metrics.failure()}.
     *
     * <p>Example:
     * <pre>{@code
     *     MetricCollectingService
     *         .builder(MeterIdPrefixFunction.ofDefault("hello"))
     *         .successFunction((context, log) -> {
     *             final int statusCode = log.responseHeaders().status().code();
     *             return (statusCode >= 200 && statusCode < 400) || statusCode == 404;
     *         })
     *         .newDecorator();
     * }</pre>
     *
     * @deprecated Use {@link ClientBuilder#successFunction(SuccessFunction)} or
     *                 {@link ServerBuilder#successFunction(SuccessFunction)}.
     */
    @Deprecated
    public AbstractMetricCollectingBuilder successFunction(
            BiPredicate<? super RequestContext, ? super RequestLog> successFunction) {
        this.successFunction = requireNonNull(successFunction, "successFunction");
        return this;
    }
}

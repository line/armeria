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

package com.linecorp.armeria.client.circuitbreaker;

import java.util.function.Function;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpResponse;

/**
 * Builds a new {@link CircuitBreakerHttpClient} or its decorator function.
 */
public final class CircuitBreakerHttpClientBuilder extends AbstractCircuitBreakerClientBuilder<HttpResponse> {

    private final boolean needsContentInStrategy;

    /**
     * Creates a new builder with the specified {@link CircuitBreakerStrategy}.
     *
     * @deprecated Use {@link CircuitBreakerHttpClient#builder(CircuitBreakerStrategy)}.
     */
    @Deprecated
    public CircuitBreakerHttpClientBuilder(CircuitBreakerStrategy strategy) {
        super(strategy);
        needsContentInStrategy = false;
    }

    /**
     * Creates a new builder with the specified {@link CircuitBreakerStrategyWithContent}.
     *
     * @deprecated Use {@link CircuitBreakerHttpClient#builder(CircuitBreakerStrategyWithContent)}.
     */
    @Deprecated
    public CircuitBreakerHttpClientBuilder(
            CircuitBreakerStrategyWithContent<HttpResponse> strategyWithContent) {
        super(strategyWithContent);
        needsContentInStrategy = true;
    }

    /**
     * Returns a newly-created {@link CircuitBreakerHttpClient} based on the properties of this builder.
     */
    public CircuitBreakerHttpClient build(HttpClient delegate) {
        if (needsContentInStrategy) {
            return new CircuitBreakerHttpClient(delegate, mapping(), strategyWithContent());
        }

        return new CircuitBreakerHttpClient(delegate, mapping(), strategy());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpClient} with a new
     * {@link CircuitBreakerHttpClient} based on the properties of this builder.
     */
    public Function<? super HttpClient, CircuitBreakerHttpClient> newDecorator() {
        return this::build;
    }

    // Methods that were overridden to change the return type.

    @Override
    public CircuitBreakerHttpClientBuilder circuitBreakerMapping(CircuitBreakerMapping mapping) {
        return mapping(mapping);
    }

    @Override
    public CircuitBreakerHttpClientBuilder mapping(CircuitBreakerMapping mapping) {
        return (CircuitBreakerHttpClientBuilder) super.mapping(mapping);
    }
}

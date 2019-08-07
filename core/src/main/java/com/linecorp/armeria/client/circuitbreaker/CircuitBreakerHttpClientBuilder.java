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

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * Builds a new {@link CircuitBreakerHttpClient} or its decorator function.
 */
public final class CircuitBreakerHttpClientBuilder
        extends CircuitBreakerClientBuilder<CircuitBreakerHttpClient, HttpRequest, HttpResponse> {

    private final boolean needsContentInStrategy;

    /**
     * Creates a new builder with the specified {@link CircuitBreakerStrategy}.
     */
    public CircuitBreakerHttpClientBuilder(CircuitBreakerStrategy strategy) {
        super(strategy);
        needsContentInStrategy = false;
    }

    /**
     * Creates a new builder with the specified {@link CircuitBreakerStrategyWithContent}.
     */
    public CircuitBreakerHttpClientBuilder(
            CircuitBreakerStrategyWithContent<HttpResponse> strategyWithContent) {
        super(strategyWithContent);
        needsContentInStrategy = true;
    }

    @Override
    public CircuitBreakerHttpClient build(Client<HttpRequest, HttpResponse> delegate) {
        if (needsContentInStrategy) {
            return new CircuitBreakerHttpClient(delegate, mapping(), strategyWithContent());
        }

        return new CircuitBreakerHttpClient(delegate, mapping(), strategy());
    }

    @Override
    public Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient> newDecorator() {
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

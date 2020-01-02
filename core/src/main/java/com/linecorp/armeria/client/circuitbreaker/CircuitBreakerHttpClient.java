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
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;

/**
 * An {@link HttpClient} decorator that handles failures of HTTP requests based on circuit breaker pattern.
 *
 * @deprecated Use {@link CircuitBreakerClient}.
 */
@Deprecated
public final class CircuitBreakerHttpClient extends CircuitBreakerClient {

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @deprecated Use {@link CircuitBreakerClient#newDecorator(CircuitBreaker, CircuitBreakerStrategy)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerStrategy strategy) {
        return newDecorator((ctx, req) -> circuitBreaker, strategy);
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerMapping} and
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @deprecated Use {@link CircuitBreakerClient#newDecorator(CircuitBreakerMapping, CircuitBreakerStrategy)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreakerMapping mapping, CircuitBreakerStrategy strategy) {
        return delegate -> new CircuitBreakerClient(delegate, mapping, strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per {@link HttpMethod} with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes an {@link HttpMethod} and creates a new {@link CircuitBreaker}
     * @deprecated Use {@link CircuitBreakerClient#newPerMethodDecorator(Function, CircuitBreakerStrategy)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerMethodDecorator(Function<String, CircuitBreaker> factory,
                          CircuitBreakerStrategy strategy) {
        return newDecorator(CircuitBreakerMapping.perMethod(factory), strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host name and creates a new {@link CircuitBreaker}
     * @deprecated Use {@link CircuitBreakerClient#newPerHostDecorator(Function, CircuitBreakerStrategy)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostDecorator(Function<String, CircuitBreaker> factory,
                        CircuitBreakerStrategy strategy) {
        return newDecorator(CircuitBreakerMapping.perHost(factory), strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and {@link HttpMethod} with
     * the specified {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host+method and creates a new {@link CircuitBreaker}
     * @deprecated Use
     *             {@link CircuitBreakerClient#newPerHostAndMethodDecorator(Function, CircuitBreakerStrategy)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostAndMethodDecorator(Function<String, CircuitBreaker> factory,
                                 CircuitBreakerStrategy strategy) {
        return newDecorator(CircuitBreakerMapping.perHostAndMethod(factory), strategy);
    }

    /**
     * Returns a new {@link CircuitBreakerHttpClientBuilder} with
     * the specified {@link CircuitBreakerStrategy}.
     *
     * @deprecated Use {@link CircuitBreakerClient#builder(CircuitBreakerStrategy)}.
     */
    @Deprecated
    public static CircuitBreakerHttpClientBuilder builder(CircuitBreakerStrategy strategy) {
        return new CircuitBreakerHttpClientBuilder(strategy);
    }

    /**
     * Returns a new {@link CircuitBreakerHttpClientBuilder} with
     * the specified {@link CircuitBreakerStrategyWithContent}.
     *
     * @deprecated Use {@link CircuitBreakerClient#builder(CircuitBreakerStrategyWithContent)}.
     */
    @Deprecated
    public static CircuitBreakerHttpClientBuilder builder(
            CircuitBreakerStrategyWithContent<HttpResponse> strategyWithContent) {
        return new CircuitBreakerHttpClientBuilder(strategyWithContent);
    }

    CircuitBreakerHttpClient(HttpClient delegate,
                             CircuitBreakerMapping mapping,
                             CircuitBreakerStrategy strategy) {
        super(delegate, mapping, strategy);
    }
}

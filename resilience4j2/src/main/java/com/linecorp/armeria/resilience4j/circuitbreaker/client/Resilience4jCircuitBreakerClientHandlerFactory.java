/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.resilience4j.circuitbreaker.client;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientHandler;
import com.linecorp.armeria.client.circuitbreaker.ClientCircuitBreakerGenerator;
import com.linecorp.armeria.common.HttpRequest;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * A {@link CircuitBreakerClientHandler} implementation for use with Resilience4j's {@link CircuitBreaker}.
 *
 * <pre>{@code
 * // using the default handler
 * CircuitBreakerRule rule = CircuitBreakerRule.onException();
 * WebClient.builder()
 *          .decorator(CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandlerFactory.of(),
 *                                                       rule))
 *          ...
 *
 *
 * // using a custom ClientCircuitBreakerGenerator
 * CircuitBreakerRule rule = CircuitBreakerRule.onException();
 * CircuitBreakerClientHandler<HttpRequest> handler = Resilience4JCircuitBreakerClientHandlerFactory.of(
 *     Resilience4jCircuitBreakerMapping.builder()
 *                                      .perHost()
 *                                      .registry(CircuitBreakerRegistry.custom()
 *                                                                      ...
 *                                                                      .build())
 *                                      .build());
 * WebClient.builder()
 *          .decorator(CircuitBreakerClient.newDecorator(handler, rule))
 *          ...
 * }</pre>
 */
public interface Resilience4jCircuitBreakerClientHandlerFactory {

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses
     * {@link Resilience4jCircuitBreakerMapping#ofDefault()} to handle requests.
     */
    static CircuitBreakerClientHandler<HttpRequest> of() {
        return new DefaultResilience4JCircuitBreakerClientHandler<>(Resilience4jCircuitBreakerMapping.ofDefault());
    }

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses
     * the provided {@link CircuitBreaker} to handle requests.
     */
    static CircuitBreakerClientHandler<HttpRequest> of(CircuitBreaker circuitBreaker) {
        return of((ctx, req) -> circuitBreaker);
    }

    /**
     * Creates a {@link CircuitBreakerClientHandler} which uses the provided
     * {@link ClientCircuitBreakerGenerator} to handle requests.
     */
    static CircuitBreakerClientHandler<HttpRequest> of(
            ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
        return new DefaultResilience4JCircuitBreakerClientHandler<>(mapping);
    }
}

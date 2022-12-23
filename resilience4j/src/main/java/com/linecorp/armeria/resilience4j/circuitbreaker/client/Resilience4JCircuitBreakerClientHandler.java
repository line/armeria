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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientHandler;
import com.linecorp.armeria.client.circuitbreaker.ClientCircuitBreakerGenerator;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * A {@link CircuitBreakerClientHandler} implementation for use with Resilience4j's {@link CircuitBreaker}.
 *
 * <pre>{@code
 * // simple example
 * CircuitBreakerRule rule = CircuitBreakerRule.onException();
 * WebClient.builder()
 *          .decorator(CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandler.of(),
 *                                                       rule))
 *          ...
 *
 *
 * // using a custom ClientCircuitBreakerGenerator
 * CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("cb");
 * CircuitBreakerRule rule = CircuitBreakerRule.onException();
 * CircuitBreakerClientHandler<HttpRequest> handler = Resilience4JCircuitBreakerClientHandler.of(
 * Resilience4jCircuitBreakerMapping.builder()
 *                                  .perHost()
 *                                  .registry(CircuitBreakerRegistry.custom()
 *                                                                  ...
 *                                                                  .build())
 *                                  .build()
);
WebClient.builder()
.decorator(CircuitBreakerClient.newDecorator(handler, rule));
 * }</pre>
 */
public final class Resilience4JCircuitBreakerClientHandler implements CircuitBreakerClientHandler<HttpRequest> {

    private static final Logger logger =
            LoggerFactory.getLogger(Resilience4JCircuitBreakerClientHandler.class);

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses
     * {@link Resilience4jCircuitBreakerMapping#ofDefault()} to handle requests.
     */
    public static CircuitBreakerClientHandler<HttpRequest> of() {
        return new Resilience4JCircuitBreakerClientHandler(Resilience4jCircuitBreakerMapping.ofDefault());
    }

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses
     * the provided {@link CircuitBreaker} to handle requests.
     */
    public static CircuitBreakerClientHandler<HttpRequest> of(CircuitBreaker circuitBreaker) {
        return of((ctx, req) -> circuitBreaker);
    }

    /**
     * Creates a {@link CircuitBreakerClientHandler} which uses the provided
     * {@link ClientCircuitBreakerGenerator} to handle requests.
     */
    public static CircuitBreakerClientHandler<HttpRequest> of(
            ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
        return new Resilience4JCircuitBreakerClientHandler(mapping);
    }

    private final ClientCircuitBreakerGenerator<CircuitBreaker> mapping;

    Resilience4JCircuitBreakerClientHandler(ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
        this.mapping = mapping;
    }

    @Override
    public CircuitBreakerCallback tryRequest(ClientRequestContext ctx, HttpRequest req) {
        final CircuitBreaker circuitBreaker;
        try {
            circuitBreaker = mapping.get(ctx, req);
        } catch (Throwable t) {
            logger.warn("Failed to get a circuit breaker from mapping", t);
            return null;
        }
        circuitBreaker.acquirePermission();
        final long startTimestamp = circuitBreaker.getCurrentTimestamp();
        return new Resilience4JCircuitBreakerCallback(circuitBreaker, startTimestamp);
    }
}

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

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientHandler;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerMapping;
import com.linecorp.armeria.client.circuitbreaker.ClientCircuitBreakerGenerator;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * A {@link CircuitBreakerClientHandler} implementation for use with Resilience4j's {@link CircuitBreaker}.
 *
 * <pre>{@code
 * // for HttpRequest
 * CircuitBreakerRule rule = CircuitBreakerRule.onException();
 * CircuitBreakerClientHandler<HttpRequest> handler = Resilience4JCircuitBreakerClientHandler.of(
 *     Resilience4jCircuitBreakerMapping.builder()
 *                                      .perHost()
 *                                      .registry(CircuitBreakerRegistry.custom()
 *                                                                      ...
 *                                                                      .build())
 *                                      .build());
 * WebClient.builder()
 *          .decorator(CircuitBreakerClient.newDecorator(handler, rule))
 *          ...
 *
 * // for RpcRequest
 * CircuitBreakerRuleWithContent rule = ...;
 * CircuitBreakerClientHandler<RpcRequest> handler = Resilience4JCircuitBreakerClientHandler.of(
 *     Resilience4jCircuitBreakerMapping.builder()
 *                                      .perHost()
 *                                      .registry(CircuitBreakerRegistry.custom()
 *                                                                      ...
 *                                                                      .build())
 *                                      .build());
 * ThriftClients.builder("localhost")
 *              .rpcDecorator(CircuitBreakerRpcClient.newDecorator(decorator, rule))
 *              ...
 * }</pre>
 */
public final class Resilience4JCircuitBreakerClientHandler<T extends Request>
        implements CircuitBreakerClientHandler<T> {

    private static final Logger logger =
            LoggerFactory.getLogger(Resilience4JCircuitBreakerClientHandler.class);

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses
     * {@link Resilience4jCircuitBreakerMapping#ofDefault()} to handle requests.
     */
    public static <I extends Request> CircuitBreakerClientHandler<I> of() {
        return of(Resilience4jCircuitBreakerMapping.ofDefault());
    }

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses the provided
     * {@link CircuitBreaker} to handle requests.
     */
    public static <I extends Request> CircuitBreakerClientHandler<I> of(CircuitBreaker circuitBreaker) {
        requireNonNull(circuitBreaker, "circuitBreaker");
        return of((ctx, req) -> circuitBreaker);
    }

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses the provided
     * {@link CircuitBreakerMapping} to handle requests.
     */
    public static <I extends Request> CircuitBreakerClientHandler<I> of(
            Resilience4jCircuitBreakerMapping mapping) {
        return new Resilience4JCircuitBreakerClientHandler<>(requireNonNull(mapping, "mapping"));
    }

    private final ClientCircuitBreakerGenerator<CircuitBreaker> mapping;

    Resilience4JCircuitBreakerClientHandler(ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
        this.mapping = mapping;
    }

    @Override
    public CircuitBreakerCallback tryRequest(ClientRequestContext ctx, T req) {
        final CircuitBreaker circuitBreaker;
        try {
            circuitBreaker = requireNonNull(mapping.get(ctx, req), "circuitBreaker");
        } catch (Throwable t) {
            logger.warn("Failed to get a circuit breaker from mapping", t);
            return null;
        }
        try {
            circuitBreaker.acquirePermission();
        } catch (CallNotPermittedException e) {
            throw UnprocessedRequestException.of(e);
        }
        final long startTimestamp = circuitBreaker.getCurrentTimestamp();
        return new Resilience4JCircuitBreakerCallback(circuitBreaker, startTimestamp);
    }
}

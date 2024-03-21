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

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;

/**
 * A handler used by a {@link CircuitBreakerClient} to integrate with a circuit breaker.
 * One may extend this interface to use a custom circuit breaker with {@link CircuitBreakerClient}.
 *
 * <pre>{@code
 * // using a pre-defined handler
 * CircuitBreakerClient.newDecorator(
 *         CircuitBreakerClientHandler.of(CircuitBreakerMapping.ofDefault()),
 *         CircuitBreakerRule.onException());
 *
 *
 * // defining a custom handler
 * CircuitBreakerClient.newDecorator(
 *         new CircuitBreakerClientHandler() {
 *             ...
 *             public CircuitBreakerCallback tryRequest(ClientRequestContext ctx, HttpRequest req) {
 *                 ...
 *                 MyCustomCircuitBreaker cb = ...
 *                 return new CircuitBreakerCallback() {
 *                     @Override
 *                     public void onSuccess(RequestContext ctx) {
 *                         cb.onSuccess();
 *                     }
 *
 *                     @Override
 *                     public void onFailure(RequestContext ctx, @Nullable Throwable throwable) {
 *                         cb.onFailure();
 *                     }
 *                 };
 *             }
 *             ...
 *         },
 *         CircuitBreakerRule.onException());
 * }</pre>
 */
@UnstableApi
@FunctionalInterface
public interface CircuitBreakerClientHandler {

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses the provided
     * {@link CircuitBreaker} to handle requests.
     */
    static CircuitBreakerClientHandler of(CircuitBreaker circuitBreaker) {
        requireNonNull(circuitBreaker, "circuitBreaker");
        return of((ctx, req) -> circuitBreaker);
    }

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses the provided
     * {@link CircuitBreakerMapping} to handle requests.
     */
    static CircuitBreakerClientHandler of(CircuitBreakerMapping mapping) {
        return new DefaultCircuitBreakerClientHandler(requireNonNull(mapping, "mapping"));
    }

    /**
     * Invoked by {@link CircuitBreakerClient} right before sending a request.
     * In a typical implementation, users may extract the appropriate circuit breaker
     * implementation using the provided {@link ClientRequestContext} and {@link Request}.
     * Afterwards, one of the following can occur:
     *
     * <ul>
     *   <li>If the circuit breaker rejects the request, an exception such as {@link FailFastException}
     *   may be thrown.</li>
     *   <li>If the circuit breaker doesn't reject the request, users may choose to return a
     *   {@link CircuitBreakerCallback}. A callback method will be invoked depending on the
     *   configured {@link CircuitBreakerRule}.</li>
     *   <li>One may return {@code null} to proceed normally as if the {@link CircuitBreakerClient}
     *   doesn't exist.</li>
     * </ul>
     */
    @Nullable
    CircuitBreakerCallback tryRequest(ClientRequestContext ctx, Request req);

    /**
     * Determines if the given {@link Exception} is related to a circuit breaker.
     * This method is invoked by the {@link CircuitBreakerClient} to determine if a
     * fallback logic should be executed.
     */
    @UnstableApi
    default boolean isCircuitBreakerException(Exception ex) {
        return ex instanceof FailFastException;
    }
}

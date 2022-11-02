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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A handler used by a {@link CircuitBreakerClient} to integrate with a CircuitBreaker.
 * One may extend this interface to use a custom CircuitBreaker with {@link CircuitBreakerClient}.
 */
@UnstableApi
public interface CircuitBreakerClientHandler<I extends Request> {

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses the provided
     * {@link CircuitBreaker} to handle requests.
     */
    static <I extends Request> CircuitBreakerClientHandler<I> of(CircuitBreaker cb) {
        return of((ctx, req) -> cb);
    }

    /**
     * Creates a default {@link CircuitBreakerClientHandler} which uses the provided
     * {@link CircuitBreakerMapping} to handle requests.
     */
    static <I extends Request> DefaultCircuitBreakerClientHandler<I> of(CircuitBreakerMapping mapping) {
        return new DefaultCircuitBreakerClientHandler<>(mapping);
    }

    /**
     * Invoked by {@link CircuitBreakerClient} right before executing a request.
     * In a typical implementation, users may extract the appropriate CircuitBreaker
     * implementation using the provided {@link ClientRequestContext} and {@link I} request.
     * Afterwards, one of the following can occur:
     *
     * <ul>
     *   <li>If the CircuitBreaker rejects the request, an exception such as {@link FailFastException}
     *   may be thrown.</li>
     *   <li>If the CircuitBreaker doesn't reject the request, users may choose to return a
     *   {@link CircuitBreakerClientCallbacks}. A callback method will be invoked depending on the
     *   configured {@link CircuitBreakerRule}.</li>
     *   <li>One may return {@code null} to proceed normally as if the {@link CircuitBreakerClient}
     *   doesn't exist.</li>
     * </ul>
     */
    @Nullable
    CircuitBreakerClientCallbacks request(ClientRequestContext ctx, I req) throws Exception;
}

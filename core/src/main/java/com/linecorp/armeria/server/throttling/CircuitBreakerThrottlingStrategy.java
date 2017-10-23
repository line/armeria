/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.server.throttling;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link ThrottlingStrategy} that throttles a request using {@link CircuitBreaker} pattern.
 */
public final class CircuitBreakerThrottlingStrategy<T extends Request> extends ThrottlingStrategy<T> {
    private final CircuitBreaker circuitBreaker;

    /**
     * Creates a new {@link ThrottlingStrategy} that determines whether a request should be throttled or not
     * using a given {@code circuitBreaker}.
     */
    public CircuitBreakerThrottlingStrategy(CircuitBreaker circuitBreaker) {
        this(circuitBreaker, null);
    }

    /**
     * Creates a new named {@link ThrottlingStrategy} that determines whether a request should be throttled
     * or not using a given {@code circuitBreaker}.
     */
    public CircuitBreakerThrottlingStrategy(CircuitBreaker circuitBreaker, String name) {
        super(name);
        this.circuitBreaker = requireNonNull(circuitBreaker, "circuitBreaker");
    }

    @Override
    public CompletableFuture<Boolean> accept(ServiceRequestContext ctx, T request) {
        return CompletableFuture.completedFuture(circuitBreaker.canRequest());
    }
}

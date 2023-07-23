/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.server.circuitbreaker;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Builds a new {@link AbstractCircuitBreakerService}.
 * @param <I> type of the request
 * @param <O> type of the response
 */
abstract class AbstractCircuitBreakerServiceBuilder<I extends Request, O extends Response> {

    @Nullable
    private CircuitBreakerRule rule;
    @Nullable
    private CircuitBreakerServiceHandler handler;
    @Nullable
    private BiFunction<? super ServiceRequestContext, ? super I, ? extends O> fallback;

    AbstractCircuitBreakerServiceBuilder(
            @Nullable CircuitBreakerRule rule,
            @Nullable CircuitBreakerServiceHandler handler,
            @Nullable BiFunction<? super ServiceRequestContext, ? super I, ? extends O> fallback) {
        this.rule = rule;
        this.handler = handler;
        this.fallback = fallback;
    }

    /**
     * Sets the {@link CircuitBreakerRule} to use.
     */
    public AbstractCircuitBreakerServiceBuilder<I, O> rule(CircuitBreakerRule rule) {
        this.handler = null;
        this.rule = requireNonNull(rule, "rule");
        return this;
    }

    /**
     * Sets the {@link CircuitBreakerServiceHandler} to handle the circuit breaker events.
     */
    public AbstractCircuitBreakerServiceBuilder<I, O> handler(CircuitBreakerServiceHandler handler) {
        this.handler = requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Sets the {@link BiFunction} to handle the fallback.
     */
    public AbstractCircuitBreakerServiceBuilder<I, O> fallback(
            BiFunction<? super ServiceRequestContext, ? super I, ? extends O> fallback) {
        this.fallback = requireNonNull(fallback, "fallback");
        return this;
    }

    public CircuitBreakerRule getRule() {
        checkState(rule != null, "rule is not set.");
        return rule;
    }

    public CircuitBreakerServiceHandler getHandler() {
        checkState(handler != null, "handler is not set.");
        return handler;
    }

    public BiFunction<? super ServiceRequestContext, ? super I, ? extends O> getFallback() {
        checkState(fallback != null, "fallback is not set.");
        return fallback;
    }
}

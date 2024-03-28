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

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Builds a new {@link CircuitBreakerService}.
 */
public final class CircuitBreakerServiceBuilder
        extends
        AbstractCircuitBreakerServiceBuilder<HttpRequest, HttpResponse> {
    CircuitBreakerServiceBuilder() {
        super(null, null, null);
    }

    /**
     * Returns a newly-created {@link CircuitBreakerService} based on the {@link CircuitBreaker} added to
     * this builder.
     */
    public CircuitBreakerService build(HttpService delegate) {
        return new CircuitBreakerService(requireNonNull(delegate, "delegate"), getRule(), getHandler(),
                                         getFallback());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link Service} with a new
     * {@link CircuitBreakerService} based on the {@link CircuitBreaker} added to this builder.
     */
    public Function<? super HttpService, CircuitBreakerService> newDecorator() {
        return service -> new CircuitBreakerService(service, getRule(), getHandler(), getFallback());
    }

    @Override
    public CircuitBreakerServiceBuilder rule(CircuitBreakerRule rule) {
        return (CircuitBreakerServiceBuilder) super.rule(rule);
    }

    @Override
    public CircuitBreakerServiceBuilder handler(
            CircuitBreakerServiceHandler handler) {
        return (CircuitBreakerServiceBuilder) super.handler(handler);
    }

    @Override
    public CircuitBreakerServiceBuilder fallback(
            BiFunction<? super ServiceRequestContext, ? super HttpRequest, ? extends HttpResponse> fallback) {
        return (CircuitBreakerServiceBuilder) super.fallback(fallback);
    }
}

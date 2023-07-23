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
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Builds a new {@link CircuitBreakerRpcService}.
 */
public final class CircuitBreakerRpcServiceBuilder
        extends AbstractCircuitBreakerServiceBuilder<RpcRequest, RpcResponse> {

    CircuitBreakerRpcServiceBuilder() {
        super(null, null, null);
    }

    /**
     * Returns a newly-created {@link CircuitBreakerRpcService} based on the {@link CircuitBreaker}s added to
     * this builder.
     */
    public CircuitBreakerRpcService build(RpcService delegate) {
        return new CircuitBreakerRpcService(requireNonNull(delegate, "delegate"),
                                            getRule(),
                                            getHandler(),
                                            getFallback()
        );
    }

    /**
     * Returns a newly-created decorator that decorates an {@link Service} with a new
     * {@link CircuitBreakerService} based on the {@link CircuitBreaker} added to this builder.
     */
    public Function<? super RpcService, CircuitBreakerRpcService> newDecorator() {
        return service -> new CircuitBreakerRpcService(service, getRule(), getHandler(), getFallback());
    }

    @Override
    public CircuitBreakerRpcServiceBuilder rule(CircuitBreakerRule rule) {
        return (CircuitBreakerRpcServiceBuilder) super.rule(rule);
    }

    @Override
    public CircuitBreakerRpcServiceBuilder handler(
            CircuitBreakerServiceHandler handler) {
        return (CircuitBreakerRpcServiceBuilder) super.handler(handler);
    }

    @Override
    public CircuitBreakerRpcServiceBuilder fallback(
            BiFunction<? super ServiceRequestContext, ? super RpcRequest, ? extends RpcResponse> fallback) {
        return (CircuitBreakerRpcServiceBuilder) super.fallback(fallback);
    }
}

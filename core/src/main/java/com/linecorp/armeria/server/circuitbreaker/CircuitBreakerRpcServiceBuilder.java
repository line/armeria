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

import java.util.function.Function;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.Service;

/**
 * Builds a new {@link CircuitBreakerRpcService}.
 */
public final class CircuitBreakerRpcServiceBuilder
        extends AbstractCircuitBreakerServiceBuilder<RpcRequest, RpcResponse> {

    /**
     * Provides default circuit breaker reject behaviour for {@link RpcRequest}.
     * Responds with {@link HttpStatusException} with {@code 503 Service Unavailable}.
     */
    private static final CircuitBreakerRejectHandler<RpcRequest, RpcResponse>
            DEFAULT_REJECT_HANDLER =
            (delegate, ctx, req) ->
                    RpcResponse.ofFailure(HttpStatusException.of(HttpStatus.SERVICE_UNAVAILABLE));

    CircuitBreakerRpcServiceBuilder(CircuitBreaker circuitBreaker) {
        super(circuitBreaker, DEFAULT_REJECT_HANDLER);
    }

    /**
     * Sets {@link CircuitBreakerAcceptHandler}.
     */
    public CircuitBreakerRpcServiceBuilder onAcceptedRequest(
            CircuitBreakerAcceptHandler<RpcRequest, RpcResponse> acceptHandler) {
        setAcceptHandler(acceptHandler);
        return this;
    }

    /**
     * Sets {@link CircuitBreakerRejectHandler}.
     */
    public CircuitBreakerRpcServiceBuilder onRejectedRequest(
            CircuitBreakerRejectHandler<RpcRequest, RpcResponse> rejectHandler) {
        setRejectHandler(rejectHandler);
        return this;
    }

    /**
     * Returns a newly-created {@link CircuitBreakerRpcService} based on the {@link CircuitBreaker}s added to
     * this builder.
     */
    public CircuitBreakerRpcService build(RpcService delegate) {
        return new CircuitBreakerRpcService(requireNonNull(delegate, "delegate"), getCircuitBreaker(),
                                            getAcceptHandler(), getRejectHandler());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link Service} with a new
     * {@link CircuitBreakerService} based on the {@link CircuitBreaker} added to this builder.
     */
    public Function<? super RpcService, CircuitBreakerRpcService> newDecorator() {
        final CircuitBreaker circuitBreaker = getCircuitBreaker();
        final CircuitBreakerAcceptHandler<RpcRequest, RpcResponse> acceptHandler = getAcceptHandler();
        final CircuitBreakerRejectHandler<RpcRequest, RpcResponse> rejectHandler = getRejectHandler();
        return service ->
                new CircuitBreakerRpcService(service, circuitBreaker, acceptHandler, rejectHandler);
    }
}

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

import java.util.function.Function;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.Service;

/**
 * Decorates an RPC {@link Service} to circuit break incoming requests.
 */
public final class CircuitBreakerRpcService extends AbstractCircuitBreakerService<RpcRequest, RpcResponse>
        implements RpcService {
    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} and
     * {@link CircuitBreakerRejectHandler}.
     *
     * @param circuitBreaker The {@link CircuitBreaker} instance to define circuit breaker
     * @param rejectHandler The {@link CircuitBreakerRejectHandler} instance to define request rejection behaviour
     */
    public static Function<? super RpcService, CircuitBreakerRpcService>
    newDecorator(CircuitBreaker circuitBreaker,
                 CircuitBreakerRejectHandler<RpcRequest, RpcResponse> rejectHandler) {
        return builder(circuitBreaker).onRejectedRequest(rejectHandler).newDecorator();
    }

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker}.
     *
     * @param circuitBreaker The {@link CircuitBreaker} instance to define circuit breaker
     */
    public static Function<? super RpcService, CircuitBreakerRpcService>
    newDecorator(CircuitBreaker circuitBreaker) {
        return builder(circuitBreaker).newDecorator();
    }

    /**
     * Returns a new {@link CircuitBreakerRpcServiceBuilder}.
     */
    public static CircuitBreakerRpcServiceBuilder builder(CircuitBreaker circuitBreaker) {
        return new CircuitBreakerRpcServiceBuilder(circuitBreaker);
    }

    /**
     * Creates a new instance that decorates the specified {@link RpcService}.
     */
    CircuitBreakerRpcService(RpcService delegate, CircuitBreaker circuitBreaker,
                             CircuitBreakerAcceptHandler<RpcRequest, RpcResponse> acceptHandler,
                             CircuitBreakerRejectHandler<RpcRequest, RpcResponse> rejectHandler) {
        super(delegate, circuitBreaker, RpcResponse::from, acceptHandler, rejectHandler);
    }
}

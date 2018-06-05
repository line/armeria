/*
 * Copyright 2018 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping.KeySelector;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * A {@link Client} decorator that handles failures of RPC remote invocation based on circuit breaker pattern.
 */
public final class CircuitBreakerRpcClient extends CircuitBreakerClient<RpcRequest, RpcResponse> {

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param circuitBreaker The {@link CircuitBreaker} instance to be used
     */
    public static Function<Client<RpcRequest, RpcResponse>, CircuitBreakerRpcClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerStrategy<RpcResponse> strategy) {
        return newDecorator((ctx, req) -> circuitBreaker, strategy);
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerMapping} and
     * {@link CircuitBreakerStrategy}.
     */
    public static Function<Client<RpcRequest, RpcResponse>, CircuitBreakerRpcClient>
    newDecorator(CircuitBreakerMapping mapping, CircuitBreakerStrategy<RpcResponse> strategy) {
        return delegate -> new CircuitBreakerRpcClient(delegate, mapping, strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per RPC method name with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * @param factory A function that takes an RPC method name and creates a new {@link CircuitBreaker}.
     */
    public static Function<Client<RpcRequest, RpcResponse>, CircuitBreakerRpcClient>
    newPerMethodDecorator(Function<String, CircuitBreaker> factory,
                          CircuitBreakerStrategy<RpcResponse> strategy) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.METHOD, factory), strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * @param factory a function that takes a host name and creates a new {@link CircuitBreaker}
     */
    public static Function<Client<RpcRequest, RpcResponse>, CircuitBreakerRpcClient>
    newPerHostDecorator(Function<String, CircuitBreaker> factory,
                        CircuitBreakerStrategy<RpcResponse> strategy) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.HOST, factory), strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and RPC method name with
     * the specified {@link CircuitBreakerStrategy}.
     *
     * @param factory a function that takes a host+method and creates a new {@link CircuitBreaker}
     */
    public static Function<Client<RpcRequest, RpcResponse>, CircuitBreakerRpcClient>
    newPerHostAndMethodDecorator(Function<String, CircuitBreaker> factory,
                                 CircuitBreakerStrategy<RpcResponse> strategy) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.HOST_AND_METHOD, factory), strategy);
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    CircuitBreakerRpcClient(Client<RpcRequest, RpcResponse> delegate, CircuitBreakerMapping mapping,
                            CircuitBreakerStrategy<RpcResponse> strategy) {
        super(delegate, mapping, strategy);
    }

    @Override
    protected RpcResponse doExecute(ClientRequestContext ctx, RpcRequest req, CircuitBreaker circuitBreaker)
            throws Exception {
        final RpcResponse response;
        try {
            response = delegate().execute(ctx, req);
        } catch (Throwable cause) {
            reportSuccessOrFailure(
                    circuitBreaker, strategy().shouldReportAsSuccess(RpcResponse.ofFailure(cause)));
            throw cause;
        }

        response.whenComplete((unused1, unused2) -> {
            reportSuccessOrFailure(circuitBreaker, strategy().shouldReportAsSuccess(response));
        });
        return response;
    }
}

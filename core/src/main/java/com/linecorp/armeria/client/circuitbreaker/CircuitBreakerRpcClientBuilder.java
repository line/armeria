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
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * Builds a new {@link CircuitBreakerRpcClient} or its decorator function.
 */
public final class CircuitBreakerRpcClientBuilder
        extends CircuitBreakerClientBuilder<CircuitBreakerRpcClient, RpcRequest, RpcResponse> {

    /**
     * Creates a new builder with the specified {@link CircuitBreakerStrategyWithContent}.
     */
    public CircuitBreakerRpcClientBuilder(CircuitBreakerStrategyWithContent<RpcResponse> strategyWithContent) {
        super(strategyWithContent);
    }

    @Override
    public CircuitBreakerRpcClient build(Client<RpcRequest, RpcResponse> delegate) {
        return new CircuitBreakerRpcClient(delegate, mapping(), strategyWithContent());
    }

    @Override
    public Function<Client<RpcRequest, RpcResponse>, CircuitBreakerRpcClient> newDecorator() {
        return this::build;
    }

    // Methods that were overridden to change the return type.

    @Override
    public CircuitBreakerRpcClientBuilder circuitBreakerMapping(CircuitBreakerMapping mapping) {
        return mapping(mapping);
    }

    @Override
    public CircuitBreakerRpcClientBuilder mapping(CircuitBreakerMapping mapping) {
        return (CircuitBreakerRpcClientBuilder) super.mapping(mapping);
    }
}

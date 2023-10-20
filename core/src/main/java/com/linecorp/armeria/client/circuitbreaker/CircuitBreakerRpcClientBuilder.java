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

import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * Builds a new {@link CircuitBreakerRpcClient} or its decorator function.
 */
public final class CircuitBreakerRpcClientBuilder
        extends AbstractCircuitBreakerClientBuilder<RpcRequest, RpcResponse> {

    CircuitBreakerRpcClientBuilder(CircuitBreakerRuleWithContent<RpcResponse> ruleWithContent) {
        super(ruleWithContent);
    }

    /**
     * Returns a newly-created {@link CircuitBreakerRpcClient} based on the properties of this builder.
     */
    public CircuitBreakerRpcClient build(RpcClient delegate) {
        return new CircuitBreakerRpcClient(delegate, handler(), ruleWithContent(), fallback());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link RpcClient} with a new
     * {@link CircuitBreakerRpcClient} based on the properties of this builder.
     */
    public Function<? super RpcClient, CircuitBreakerRpcClient> newDecorator() {
        return this::build;
    }

    // Methods that were overridden to change the return type.

    @Override
    public CircuitBreakerRpcClientBuilder mapping(CircuitBreakerMapping mapping) {
        return (CircuitBreakerRpcClientBuilder) super.mapping(mapping);
    }

    @Override
    public CircuitBreakerRpcClientBuilder handler(CircuitBreakerClientHandler handler) {
        return (CircuitBreakerRpcClientBuilder) super.handler(handler);
    }

    @Override
    public CircuitBreakerRpcClientBuilder recover(BiFunction<? super ClientRequestContext, ? super RpcRequest,
            ? extends RpcResponse> fallback) {
        return (CircuitBreakerRpcClientBuilder) super.recover(fallback);
    }
}

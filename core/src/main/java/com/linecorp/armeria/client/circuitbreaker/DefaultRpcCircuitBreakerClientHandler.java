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
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.circuitbreaker.AbstractCircuitBreakerClientHandler;

import io.netty.util.AttributeKey;

class DefaultRpcCircuitBreakerClientHandler
        extends AbstractCircuitBreakerClientHandler<CircuitBreaker, RpcRequest, RpcResponse> {

    private static final AttributeKey<CircuitBreaker> CIRCUIT_BREAKER =
            AttributeKey.valueOf(DefaultRpcCircuitBreakerClientHandler.class, "CIRCUIT_BREAKER");

    DefaultRpcCircuitBreakerClientHandler(ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
        super(mapping);
    }

    private static CircuitBreaker circuitBreaker(RequestContext ctx) {
        final CircuitBreaker circuitBreaker = ctx.attr(CIRCUIT_BREAKER);
        if (circuitBreaker == null) {
            throw new IllegalStateException("Attempting to report to a null CircuitBreaker");
        }
        return circuitBreaker;
    }

    @Override
    protected void circuitBreaker(RequestContext ctx, CircuitBreaker circuitBreaker) {
        ctx.setAttr(CIRCUIT_BREAKER, circuitBreaker);
    }

    @Override
    protected void tryRequest(RequestContext ctx, CircuitBreaker circuitBreaker) {
        if (!circuitBreaker.tryRequest()) {
            throw new FailFastException(circuitBreaker);
        }
    }

    @Override
    public void onSuccess(ClientRequestContext ctx) {
        circuitBreaker(ctx).onSuccess();
    }

    @Override
    public void onFailure(ClientRequestContext ctx, @Nullable Throwable cause) {
        circuitBreaker(ctx).onFailure();
    }
}

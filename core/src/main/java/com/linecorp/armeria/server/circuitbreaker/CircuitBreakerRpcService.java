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

import java.util.function.BiFunction;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Decorates an RPC {@link Service} to circuit break incoming requests.
 */
public final class CircuitBreakerRpcService extends AbstractCircuitBreakerService<RpcRequest, RpcResponse>
        implements RpcService {

    /**
     * Returns a new {@link CircuitBreakerRpcServiceBuilder}.
     */
    public static CircuitBreakerRpcServiceBuilder builder() {
        return new CircuitBreakerRpcServiceBuilder();
    }

    /**
     * Creates a new instance that decorates the specified {@link RpcService}.
     */
    CircuitBreakerRpcService(
            RpcService delegate, CircuitBreakerRule rule, CircuitBreakerServiceHandler handler,
            BiFunction<? super ServiceRequestContext, ? super RpcRequest, ? extends RpcResponse> fallback) {
        super(delegate, rule, handler, fallback);
    }

    @Override
    protected RpcResponse doServe(ServiceRequestContext ctx, RpcRequest req, CircuitBreakerCallback callback)
            throws Exception {
        return unwrap().serve(ctx, req);
    }
}

/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * A decorating {@link RpcService} which implements its {@link #serve(ServiceRequestContext, RpcRequest)}
 * method using a given function.
 *
 * @see RpcService#decorate(DecoratingRpcServiceFunction)
 */
final class FunctionalDecoratingRpcService extends SimpleDecoratingRpcService {

    private final DecoratingRpcServiceFunction function;

    /**
     * Creates a new instance with the specified function.
     */
    FunctionalDecoratingRpcService(RpcService delegate, DecoratingRpcServiceFunction function) {
        super(delegate);
        this.function = requireNonNull(function, "function");
    }

    @Override
    public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
        return function.serve(delegate(), ctx, req);
    }

    @Override
    public String toString() {
        return FunctionalDecoratingRpcService.class.getSimpleName() + '(' + delegate() + ", " + function + ')';
    }
}

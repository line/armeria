/*
 *  Copyright 2019 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * A decorating {@link RpcClient} which implements its {@link #execute(ClientRequestContext, RpcRequest)}
 * method using a given function.
 *
 * @see ClientBuilder#rpcDecorator(DecoratingRpcClientFunction)
 */
final class FunctionalDecoratingRpcClient extends SimpleDecoratingRpcClient {

    private final DecoratingRpcClientFunction function;

    /**
     * Creates a new instance with the specified function.
     */
    FunctionalDecoratingRpcClient(RpcClient delegate, DecoratingRpcClientFunction function) {
        super(delegate);
        this.function = requireNonNull(function, "function");
    }

    @Override
    public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        return function.execute((RpcClient) unwrap(), ctx, req);
    }

    @Override
    public String toString() {
        return FunctionalDecoratingRpcClient.class.getSimpleName() + '(' + unwrap() + ", " + function + ')';
    }
}

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

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * A functional interface that enables building a {@link SimpleDecoratingRpcClient} with
 * {@link ClientBuilder#rpcDecorator(DecoratingRpcClientFunction)}.
 */
@FunctionalInterface
public interface DecoratingRpcClientFunction {
    /**
     * Sends an {@link RpcRequest} to a remote {@link Endpoint}, as specified in
     * {@link ClientRequestContext#endpoint()}.
     *
     * @param delegate the {@link RpcClient} being decorated by this function
     * @param ctx the context of the {@link RpcRequest} being sent
     * @param req the {@link RpcRequest} being sent
     *
     * @return the {@link RpcResponse} to be received
     */
    RpcResponse execute(RpcClient delegate, ClientRequestContext ctx, RpcRequest req) throws Exception;
}

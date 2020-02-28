/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.throttling;

import java.util.function.Function;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.Service;

/**
 * Decorates an RPC {@link Service} to throttle incoming requests.
 */
public final class ThrottlingRpcService extends AbstractThrottlingService<RpcRequest, RpcResponse>
        implements RpcService {
    /**
     * Creates a new decorator using the specified {@link ThrottlingStrategy} and
     * {@link ThrottlingRejectHandler}.
     *
     * @param strategy The {@link ThrottlingStrategy} instance to define throttling strategy
     * @param rejectHandler The {@link ThrottlingRejectHandler} instance to define request rejection behaviour
     */
    public static Function<? super RpcService, ThrottlingRpcService>
    newDecorator(ThrottlingStrategy<RpcRequest> strategy,
                 ThrottlingRejectHandler<RpcRequest, RpcResponse> rejectHandler) {
        return builder(strategy).onRejectedRequest(rejectHandler).newDecorator();
    }

    /**
     * Creates a new decorator using the specified {@link ThrottlingStrategy}.
     *
     * @param strategy The {@link ThrottlingStrategy} instance to define throttling strategy
     */
    public static Function<? super RpcService, ThrottlingRpcService>
    newDecorator(ThrottlingStrategy<RpcRequest> strategy) {
        return builder(strategy).newDecorator();
    }

    /**
     * Returns a new {@link ThrottlingRpcServiceBuilder}.
     */
    public static ThrottlingRpcServiceBuilder builder(ThrottlingStrategy<RpcRequest> strategy) {
        return new ThrottlingRpcServiceBuilder(strategy);
    }

    /**
     * Creates a new instance that decorates the specified {@link RpcService}.
     */
    ThrottlingRpcService(RpcService delegate, ThrottlingStrategy<RpcRequest> strategy,
                         ThrottlingAcceptHandler<RpcRequest, RpcResponse> acceptHandler,
                         ThrottlingRejectHandler<RpcRequest, RpcResponse> rejectHandler) {
        super(delegate, strategy, RpcResponse::from, acceptHandler, rejectHandler);
    }
}

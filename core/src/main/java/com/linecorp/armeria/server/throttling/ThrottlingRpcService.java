/*
 * Copyright 2017 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Decorates an RPC {@link Service} to throttle incoming requests.
 */
public final class ThrottlingRpcService extends AbstractThrottlingService<RpcRequest, RpcResponse>
        implements RpcService {
    /**
     * Creates a new decorator using the specified {@link ThrottlingStrategy} instance.
     *
     * @param strategy The {@link ThrottlingStrategy} instance to be used
     */
    public static Function<? super RpcService, ThrottlingRpcService>
    newDecorator(ThrottlingStrategy<RpcRequest> strategy) {
        requireNonNull(strategy, "strategy");
        return delegate -> new ThrottlingRpcService(delegate, strategy);
    }

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    protected ThrottlingRpcService(RpcService delegate, ThrottlingStrategy<RpcRequest> strategy) {
        super(delegate, strategy, RpcResponse::from);
    }

    /**
     * Invoked when {@code req} is throttled. By default, this method responds with a
     * {@link HttpStatusException} with {@code 503 Service Unavailable}.
     */
    @Override
    protected RpcResponse onFailure(ServiceRequestContext ctx, RpcRequest req, @Nullable Throwable cause)
            throws Exception {
        return RpcResponse.ofFailure(HttpStatusException.of(HttpStatus.SERVICE_UNAVAILABLE));
    }
}

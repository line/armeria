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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.Service;

/**
 * Builds a new {@link ThrottlingRpcService}.
 */
public final class ThrottlingRpcServiceBuilder
        extends AbstractThrottlingServiceBuilder<RpcRequest, RpcResponse> {

    /**
     * Provides default throttling reject behaviour for {@link RpcRequest}.
     * Responds with {@link HttpStatusException} with {@code 503 Service Unavailable}.
     */
    private static final ThrottlingRejectHandler<RpcRequest, RpcResponse> DEFAULT_REJECT_HANDLER =
            (delegate, ctx, req, cause) ->
                    RpcResponse.ofFailure(HttpStatusException.of(HttpStatus.SERVICE_UNAVAILABLE));

    ThrottlingRpcServiceBuilder(ThrottlingStrategy<RpcRequest> strategy) {
        super(strategy, DEFAULT_REJECT_HANDLER);
    }

    /**
     * Sets {@link ThrottlingAcceptHandler}.
     */
    public ThrottlingRpcServiceBuilder onAcceptedRequest(
            ThrottlingAcceptHandler<RpcRequest, RpcResponse> acceptHandler) {
        setAcceptHandler(acceptHandler);
        return this;
    }

    /**
     * Sets {@link ThrottlingRejectHandler}.
     */
    public ThrottlingRpcServiceBuilder onRejectedRequest(
            ThrottlingRejectHandler<RpcRequest, RpcResponse> rejectHandler) {
        setRejectHandler(rejectHandler);
        return this;
    }

    /**
     * Returns a newly-created {@link ThrottlingRpcService} based on the {@link ThrottlingStrategy}s added to
     * this builder.
     */
    public ThrottlingRpcService build(RpcService delegate) {
        return new ThrottlingRpcService(requireNonNull(delegate, "delegate"), getStrategy(),
                                        getAcceptHandler(), getRejectHandler());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link Service} with a new
     * {@link ThrottlingService} based on the {@link ThrottlingStrategy}s added to this builder.
     */
    public Function<? super RpcService, ThrottlingRpcService> newDecorator() {
        final ThrottlingStrategy<RpcRequest> strategy = getStrategy();
        final ThrottlingAcceptHandler<RpcRequest, RpcResponse> acceptHandler = getAcceptHandler();
        final ThrottlingRejectHandler<RpcRequest, RpcResponse> rejectHandler = getRejectHandler();
        return service ->
                new ThrottlingRpcService(service, strategy, acceptHandler, rejectHandler);
    }
}

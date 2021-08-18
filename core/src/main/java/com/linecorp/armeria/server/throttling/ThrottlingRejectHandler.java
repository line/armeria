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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A callback which is invoked to handle rejected (failure) requests indicated by {@link ThrottlingStrategy}.
 *
 * @param <I> the type of incoming {@link Request}. Must be {@link HttpRequest} or {@link RpcRequest}.
 * @param <O> the type of outgoing {@link Response}. Must be {@link HttpResponse} or {@link RpcResponse}.
 *
 * @see ThrottlingServiceBuilder#onRejectedRequest(ThrottlingRejectHandler)
 */
@FunctionalInterface
public interface ThrottlingRejectHandler<I extends Request, O extends Response> {
    /**
     * Invoked when the {@link ThrottlingStrategy} rejects the specified {@link Request}.
     *
     * @param delegate the next {@link Service} in the decoration chain
     * @param ctx the {@link ServiceRequestContext} of {@code req}
     * @param req the {@link Request} being rejected
     * @param cause {@code null} if {@code req} has been rejected by the {@link ThrottlingStrategy}.
     *              non-{@code null} if the {@link ThrottlingStrategy} raised an {@link Exception}.
     * @throws Exception when something wrong happens
     */
    O handleRejected(Service<I, O> delegate, ServiceRequestContext ctx, I req, @Nullable Throwable cause)
            throws Exception;
}

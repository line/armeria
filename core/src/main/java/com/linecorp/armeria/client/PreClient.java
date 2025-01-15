/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Prepares a {@link Request} before sending it to a remote {@link Endpoint}.
 *
 * <p>Note that this interface is not a user's entry point for sending a {@link Request}. It is rather
 * a generic request processor interface which intercepts a {@link Request}.
 * A user should implement {@link Preprocessor} and add it to the client instead.
 *
 * @param <I> the type of outgoing {@link Request}. Must be {@link HttpRequest} or {@link RpcRequest}.
 * @param <O> the type of incoming {@link Response}. Must be {@link HttpResponse} or {@link RpcResponse}.
 */
@UnstableApi
@FunctionalInterface
public interface PreClient<I extends Request, O extends Response> {

    /**
     * Prepares a {@link Request} before sending it to a remote {@link Endpoint}.
     *
     * @return the {@link Response} to the specified {@link Request}
     */
    O execute(PreClientRequestContext ctx, I req) throws Exception;
}

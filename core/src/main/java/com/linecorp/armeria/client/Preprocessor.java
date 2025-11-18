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

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * Decorates a {@link PreClient}. Use either {@link HttpPreClient} or {@link RpcPreClient}
 * depending on whether the client is HTTP-based or RPC-based.
 *
 * @param <I> the {@link Request} type of the {@link Client} being decorated
 * @param <O> the {@link Response} type of the {@link Client} being decorated
 */
@FunctionalInterface
public interface Preprocessor<I extends Request, O extends Response> {

    /**
     * Creates a new instance that decorates the specified {@link PreClient}.
     */
    O execute(PreClient<I, O> delegate, PreClientRequestContext ctx, I req) throws Exception;
}

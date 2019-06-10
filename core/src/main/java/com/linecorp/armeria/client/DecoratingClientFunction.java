/*
 *  Copyright 2017 LINE Corporation
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

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * A functional interface that enables building a {@link SimpleDecoratingClient} with
 * {@link ClientBuilder#decorator(DecoratingClientFunction)} and
 * {@link ClientBuilder#rpcDecorator(DecoratingClientFunction)}.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
@FunctionalInterface
public interface DecoratingClientFunction<I extends Request, O extends Response> {
    /**
     * Sends a {@link Request} to a remote {@link Endpoint}, as specified in
     * {@link ClientRequestContext#endpoint()}.
     *
     * @param delegate the {@link Client} being decorated by this function
     * @param ctx the context of the {@link Request} being sent
     * @param req the {@link Request} being sent
     *
     * @return the {@link Response} to be received
     */
    O execute(Client<I, O> delegate, ClientRequestContext ctx, I req) throws Exception;
}

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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * A functional interface that enables building a {@link SimpleDecoratingHttpClient} with
 * {@link ClientBuilder#decorator(DecoratingHttpClientFunction)}.
 */
@FunctionalInterface
public interface DecoratingHttpClientFunction {
    /**
     * Sends an {@link HttpRequest} to a remote {@link Endpoint}, as specified in
     * {@link ClientRequestContext#endpoint()}.
     *
     * @param delegate the {@link HttpClient} being decorated by this function
     * @param ctx the context of the {@link HttpRequest} being sent
     * @param req the {@link HttpRequest} being sent
     *
     * @return the {@link HttpResponse} to be received
     */
    HttpResponse execute(HttpClient delegate, ClientRequestContext ctx, HttpRequest req) throws Exception;
}

/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.http;

import com.linecorp.armeria.client.ClientOptionDerivable;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;

import io.netty.util.concurrent.Future;

/**
 * A simple HTTP client that can send a {@link SimpleHttpRequest} to an HTTP/1 or 2 server.
 *
 * @deprecated Use {@link HttpClient#execute(AggregatedHttpMessage)} instead.
 * @see SimpleHttpRequestBuilder
 */
@Deprecated
public interface SimpleHttpClient extends ClientOptionDerivable<SimpleHttpClient> {
    /**
     * Sends the specified {@code request} to the HTTP server asynchronously.
     *
     * @return the {@link Future} that is notified when a {@link SimpleHttpResponse} is received or
     *         when the request fails.
     */
    Future<SimpleHttpResponse> execute(SimpleHttpRequest request);
}

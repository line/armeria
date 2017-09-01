/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

import java.util.concurrent.CompletableFuture;

final class HttpRequestAggregator extends HttpMessageAggregator {

    private final HttpRequest request;
    private HttpHeaders trailingHeaders;

    HttpRequestAggregator(HttpRequest request, CompletableFuture<AggregatedHttpMessage> future) {
        super(future);
        this.request = request;
        trailingHeaders = HttpHeaders.EMPTY_HEADERS;
    }

    @Override
    protected void onHeaders(HttpHeaders headers) {
        trailingHeaders = headers;
    }

    @Override
    protected AggregatedHttpMessage onSuccess(HttpData content) {
        return AggregatedHttpMessage.of(request.headers(), content, trailingHeaders);
    }

    @Override
    protected void onFailure() {
        trailingHeaders = HttpHeaders.EMPTY_HEADERS;
    }
}

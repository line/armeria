/*
 * Copyright 2023 LINE Corporation
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
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.stream.DeferredStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.util.concurrent.EventExecutor;

final class DeferredHttpRequest extends DeferredStreamMessage<HttpObject> implements HttpRequest {

    private final RequestHeaders headers;

    DeferredHttpRequest(RequestHeaders headers) {
        this.headers = headers;
    }

    DeferredHttpRequest(RequestHeaders headers, EventExecutor executor) {
        super(executor);
        this.headers = headers;
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    void delegateWhenComplete(CompletionStage<? extends StreamMessage<? extends HttpObject>> stage) {
        //noinspection unchecked
        delegateWhenCompleteStage((CompletionStage<? extends StreamMessage<HttpObject>>) stage);
    }

    @SuppressWarnings("unchecked")
    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregate(AggregationOptions options) {
        return super.aggregate(options);
    }
}

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

package com.linecorp.armeria.internal.common;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

public final class HttpRequestAggregator extends HttpMessageAggregator<AggregatedHttpRequest> {

    private final HttpRequest request;
    private HttpHeaders trailers;

    public HttpRequestAggregator(HttpRequest request, CompletableFuture<AggregatedHttpRequest> future,
                                 @Nullable ByteBufAllocator alloc) {
        super(future, alloc);
        this.request = request;
        trailers = HttpHeaders.of();
    }

    @Override
    protected void onHeaders(HttpHeaders headers) {
        if (headers.isEmpty()) {
            return;
        }

        if (trailers.isEmpty()) {
            trailers = headers;
        } else {
            // Optionally, only one trailers can be present.
            // See https://tools.ietf.org/html/rfc7540#section-8.1
        }
    }

    @Override
    protected void onData(HttpData data) {
        if (!trailers.isEmpty()) {
            ReferenceCountUtil.safeRelease(data);
            // Data can't come after trailers.
            // See https://tools.ietf.org/html/rfc7540#section-8.1
            return;
        }
        super.onData(data);
    }

    @Override
    protected AggregatedHttpRequest onSuccess(HttpData content) {
        return AggregatedHttpRequest.of(request.headers(), content, trailers);
    }

    @Override
    protected void onFailure() {
        trailers = HttpHeaders.of();
    }
}

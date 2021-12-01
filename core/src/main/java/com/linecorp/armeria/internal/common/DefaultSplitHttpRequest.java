/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SplitHttpRequest;

import io.netty.util.concurrent.EventExecutor;

public class DefaultSplitHttpRequest extends AbstractSplitHttpMessage implements SplitHttpRequest {

    private static final HttpHeaders EMPTY_TRAILERS = HttpHeaders.of();

    private final RequestHeaders headers;

    public DefaultSplitHttpRequest(HttpRequest request, EventExecutor executor) {
        super(request, executor, new SplitHttpRequestBodySubscriber(request, executor));
        headers = request.headers();
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    private static final class SplitHttpRequestBodySubscriber extends BodySubscriber {

        private SplitHttpRequestBodySubscriber(HttpRequest request, EventExecutor executor) {
            super(0, request, executor);
        }

        @Override
        public void onComplete() {
            completeTrailers(EMPTY_TRAILERS);
            super.onComplete();
        }

        @Override
        public void onError(Throwable cause) {
            completeTrailers(EMPTY_TRAILERS);
            super.onError(cause);
        }
    }
}

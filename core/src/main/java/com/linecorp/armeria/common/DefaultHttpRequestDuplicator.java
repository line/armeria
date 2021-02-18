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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.stream.DefaultStreamMessageDuplicator;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageWrapper;

import io.netty.util.concurrent.EventExecutor;

final class DefaultHttpRequestDuplicator
        extends DefaultStreamMessageDuplicator<HttpObject> implements HttpRequestDuplicator {

    private final HttpRequest req;

    DefaultHttpRequestDuplicator(HttpRequest req, EventExecutor executor, long maxRequestLength) {
        super(requireNonNull(req, "req"), obj -> {
            if (obj instanceof HttpData) {
                return ((HttpData) obj).length();
            }
            return 0;
        }, executor, maxRequestLength);
        this.req = req;
    }

    @Override
    public HttpRequest duplicate() {
        return duplicate(req.headers());
    }

    @Override
    public HttpRequest duplicate(RequestHeaders newHeaders) {
        requireNonNull(newHeaders, "newHeaders");
        return new DuplicatedHttpRequest(super.duplicate(), newHeaders, req.options());
    }

    private class DuplicatedHttpRequest
            extends StreamMessageWrapper<HttpObject> implements HttpRequest {

        private final RequestHeaders headers;
        private final RequestOptions options;

        DuplicatedHttpRequest(StreamMessage<? extends HttpObject> delegate,
                              RequestHeaders headers, RequestOptions options) {
            super(delegate);
            this.headers = headers;
            this.options = options;
        }

        @Override
        public RequestHeaders headers() {
            return headers;
        }

        @Override
        public RequestOptions options() {
            return options;
        }

        // Override to return HttpRequestDuplicator.

        @Override
        public HttpRequestDuplicator toDuplicator() {
            return toDuplicator(duplicatorExecutor());
        }

        @Override
        public HttpRequestDuplicator toDuplicator(EventExecutor executor) {
            return HttpRequest.super.toDuplicator(executor);
        }

        @Override
        public EventExecutor defaultSubscriberExecutor() {
            return duplicatorExecutor();
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("headers", headers)
                              .toString();
        }
    }
}

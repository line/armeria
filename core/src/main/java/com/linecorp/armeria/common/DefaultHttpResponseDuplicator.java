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

import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.stream.DefaultStreamMessageDuplicator;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageWrapper;

import io.netty.util.concurrent.EventExecutor;

final class DefaultHttpResponseDuplicator
        extends DefaultStreamMessageDuplicator<HttpObject> implements HttpResponseDuplicator {

    DefaultHttpResponseDuplicator(HttpResponse res, EventExecutor executor, long maxResponseLength) {
        super(requireNonNull(res, "res"), obj -> {
            if (obj instanceof HttpData) {
                return ((HttpData) obj).length();
            }
            return 0;
        }, executor, maxResponseLength);
    }

    @Override
    public HttpResponse duplicate() {
        return new DuplicatedHttpResponse(super.duplicate());
    }

    private class DuplicatedHttpResponse
            extends StreamMessageWrapper<HttpObject> implements HttpResponse {

        DuplicatedHttpResponse(StreamMessage<? extends HttpObject> delegate) {
            super(delegate);
        }

        // Override to return HttpResponseDuplicator.

        @SuppressWarnings("unchecked")
        @Override
        public CompletableFuture<AggregatedHttpResponse> aggregate(AggregationOptions options) {
            return super.aggregate(options);
        }

        @Override
        public HttpResponseDuplicator toDuplicator() {
            return HttpResponse.super.toDuplicator();
        }

        @Override
        public HttpResponseDuplicator toDuplicator(EventExecutor executor) {
            return HttpResponse.super.toDuplicator(executor);
        }

        @Override
        public EventExecutor defaultSubscriberExecutor() {
            return duplicatorExecutor();
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).toString();
        }
    }
}

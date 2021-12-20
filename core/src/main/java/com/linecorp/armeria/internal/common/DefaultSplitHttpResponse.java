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

import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;

import io.netty.util.concurrent.EventExecutor;

public class DefaultSplitHttpResponse extends AbstractSplitHttpMessage implements SplitHttpResponse {

    private static final ResponseHeaders HEADERS_WITH_UNKNOWN_STATUS = ResponseHeaders.of(HttpStatus.UNKNOWN);
    private static final HeadersFuture<HttpHeaders> EMPTY_TRAILERS;

    static {
        EMPTY_TRAILERS = new HeadersFuture<>();
        EMPTY_TRAILERS.doComplete(HttpHeaders.of());
    }

    private final SplitHttpResponseBodySubscriber bodySubscriber;

    public DefaultSplitHttpResponse(HttpResponse response, EventExecutor executor) {
        this(response, executor, new SplitHttpResponseBodySubscriber(response, executor));
    }

    private DefaultSplitHttpResponse(HttpResponse response, EventExecutor executor,
                                     SplitHttpResponseBodySubscriber bodySubscriber) {
        super(response, executor, bodySubscriber);
        this.bodySubscriber = bodySubscriber;
    }

    @Override
    public final CompletableFuture<ResponseHeaders> headers() {
        return bodySubscriber.headersFuture();
    }

    private static final class SplitHttpResponseBodySubscriber extends SplitHttpMessageSubscriber {

        private final HeadersFuture<ResponseHeaders> headersFuture = new HeadersFuture<>();

        SplitHttpResponseBodySubscriber(HttpResponse response, EventExecutor executor) {
            super(1, response, executor);
        }

        CompletableFuture<ResponseHeaders> headersFuture() {
            return headersFuture;
        }

        @Override
        public void onNext(HttpObject httpObject) {
            if (httpObject instanceof ResponseHeaders) {
                final ResponseHeaders headers = (ResponseHeaders) httpObject;
                final HttpStatus status = headers.status();
                if (status.isInformational()) {
                    // Ignore informational headers
                    final Subscription upstream = upstream();
                    assert upstream != null;
                    upstream.request(1);
                } else {
                    headersFuture.doComplete(headers);
                }
                return;
            }
            super.onNext(httpObject);
        }

        private void maybeCompleteHeaders(@Nullable Throwable cause) {
            if (!headersFuture.isDone()) {
                if (cause != null && !(cause instanceof CancelledSubscriptionException) &&
                    !(cause instanceof AbortedStreamException)) {
                    headersFuture.doCompleteExceptionally(cause);
                } else {
                    headersFuture.doComplete(HEADERS_WITH_UNKNOWN_STATUS);
                }
            }
        }

        @Override
        protected void doOnCompletion(@Nullable Throwable cause) {
            maybeCompleteHeaders(cause);
            super.doOnCompletion(cause);
        }
    }
}

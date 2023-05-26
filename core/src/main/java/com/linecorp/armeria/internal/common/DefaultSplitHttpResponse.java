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
import java.util.function.Predicate;

import org.reactivestreams.Subscription;

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

    private final SplitHttpResponseBodySubscriber bodySubscriber;

    public DefaultSplitHttpResponse(HttpResponse response, EventExecutor upstreamExecutor) {
        this(response, upstreamExecutor, headers -> !headers.status().isInformational());
    }

    public DefaultSplitHttpResponse(HttpResponse response, EventExecutor upstreamExecutor,
                                    Predicate<ResponseHeaders> responseHeadersPredicate) {
        this(response, upstreamExecutor,
             new SplitHttpResponseBodySubscriber(response, upstreamExecutor, responseHeadersPredicate));
    }

    private DefaultSplitHttpResponse(HttpResponse response, EventExecutor upstreamExecutor,
                                     SplitHttpResponseBodySubscriber bodySubscriber) {
        super(response, upstreamExecutor, bodySubscriber);
        this.bodySubscriber = bodySubscriber;
    }

    @Override
    public final CompletableFuture<ResponseHeaders> headers() {
        return bodySubscriber.headersFuture();
    }

    private static final class SplitHttpResponseBodySubscriber extends SplitHttpMessageSubscriber {

        private final HeadersFuture<ResponseHeaders> headersFuture = new HeadersFuture<>();
        private final Predicate<ResponseHeaders> responseHeadersPredicate;

        SplitHttpResponseBodySubscriber(HttpResponse response, EventExecutor upstreamExecutor,
                                        Predicate<ResponseHeaders> responseHeadersPredicate) {
            super(1, response, upstreamExecutor);
            this.responseHeadersPredicate = responseHeadersPredicate;
        }

        CompletableFuture<ResponseHeaders> headersFuture() {
            return headersFuture;
        }

        @Override
        public void onNext(HttpObject httpObject) {
            if (httpObject instanceof ResponseHeaders) {
                final ResponseHeaders headers = (ResponseHeaders) httpObject;
                if (responseHeadersPredicate.test(headers)) {
                    headersFuture.doComplete(headers);
                } else {
                    final Subscription upstream = upstream();
                    assert upstream != null;
                    upstream.request(1);
                }
                return;
            }
            super.onNext(httpObject);
        }

        @Override
        protected void doOnCompletion(@Nullable Throwable cause) {
            if (!headersFuture.isDone()) {
                if (cause != null && !(cause instanceof CancelledSubscriptionException) &&
                    !(cause instanceof AbortedStreamException)) {
                    headersFuture.doCompleteExceptionally(cause);
                } else {
                    headersFuture.doComplete(HEADERS_WITH_UNKNOWN_STATUS);
                }
            }
        }
    }
}

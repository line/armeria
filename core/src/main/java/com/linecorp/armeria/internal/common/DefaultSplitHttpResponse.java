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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.util.concurrent.EventExecutor;

public class DefaultSplitHttpResponse extends AbstractSplitHttpMessage implements SplitHttpResponse {

    private static final ResponseHeaders HEADERS_WITH_UNKNOWN_STATUS = ResponseHeaders.of(HttpStatus.UNKNOWN);
    private static final HeadersFuture<HttpHeaders> EMPTY_TRAILERS;

    static {
        EMPTY_TRAILERS = new HeadersFuture<>();
        EMPTY_TRAILERS.doComplete(HttpHeaders.of());
    }

    private final HeadersFuture<ResponseHeaders> headersFuture = new HeadersFuture<>();
    private final BodySubscriber bodySubscriber = new SplitHttpResponseBodySubscriber();
    private final HttpResponse response;

    public DefaultSplitHttpResponse(HttpResponse response, EventExecutor executor) {
        super(response, executor);
        this.response = requireNonNull(response, "response");

        response.subscribe(bodySubscriber, upstreamExecutor, SubscriptionOption.values());
    }

    @Override
    public final CompletableFuture<ResponseHeaders> headers() {
        return headersFuture;
    }

    @Override
    public final StreamMessage<HttpData> body() {
        return this;
    }

    @Override
    public final CompletableFuture<HttpHeaders> trailers() {
        HeadersFuture<HttpHeaders> trailersFuture = this.trailersFuture;
        if (trailersFuture != null) {
            return trailersFuture;
        }

        trailersFuture = new HeadersFuture<>();
        if (trailersFutureUpdater.compareAndSet(this, null, trailersFuture)) {
            return trailersFuture;
        } else {
            return this.trailersFuture;
        }
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        if (!downstreamUpdater.compareAndSet(bodySubscriber, null, subscriber)) {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(new IllegalStateException("subscribed by other subscriber already"));
            return;
        }

        if (executor.inEventLoop()) {
            bodySubscriber.initDownstream(subscriber, executor, options);
        } else {
            executor.execute(() -> bodySubscriber.initDownstream(subscriber, executor, options));
        }
    }

    private final class SplitHttpResponseBodySubscriber extends BodySubscriber {

        // 1 is used for prefetching headers
        private long pendingRequests = 1;

        @Override
        public void onSubscribe(Subscription subscription) {
            super.onSubscribe(subscription);
            subscription.request(pendingRequests);
        }

        @Override
        protected void request0(long n) {
            final Subscription upstream = this.upstream;
            if (upstream == null) {
                pendingRequests = LongMath.saturatedAdd(n, pendingRequests);
            } else {
                upstream.request(n);
            }
        }

        @Override
        public void onNext(HttpObject httpObject) {
            if (httpObject instanceof ResponseHeaders) {
                final ResponseHeaders headers = (ResponseHeaders) httpObject;
                final HttpStatus status = headers.status();
                if (status.isInformational()) {
                    // Ignore informational headers
                    upstream.request(1);
                } else {
                    headersFuture.doComplete(headers);
                }
                return;
            }
            super.onNext(httpObject);
        }

        @Override
        public void onComplete() {
            maybeCompleteHeaders(null);
            super.onComplete();
        }

        @Override
        public void onError(Throwable cause) {
            maybeCompleteHeaders(cause);
            super.onError(cause);
        }

        @Override
        public void cancel() {
            if (cancelCalled) {
                return;
            }
            cancelCalled = true;
            if (!notifyCancellation) {
                downstream = NoopSubscriber.get();
            }
            maybeCompleteHeaders(null);
            final Subscription upstream = this.upstream;
            if (upstream != null) {
                upstream.cancel();
            }
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

            if (trailersFuture == null) {
                trailersFutureUpdater.compareAndSet(DefaultSplitHttpResponse.this, null, EMPTY_TRAILERS);
            }
        }
    }
}

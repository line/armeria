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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.util.concurrent.EventExecutor;

final class DefaultHttpResponseBodyStream implements HttpResponseBodyStream {

    private static final AtomicReferenceFieldUpdater<BodySubscriber, Subscription> upstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(BodySubscriber.class, Subscription.class, "upstream");

    private static final ResponseHeaders HEADERS_WITH_UNKNOWN_STATUS = ResponseHeaders.of(HttpStatus.UNKNOWN);
    private static final HeadersFuture<List<ResponseHeaders>> EMPTY_INFORMATIONAL_HEADERS;
    private static final HeadersFuture<HttpHeaders> EMPTY_TRAILERS;

    static {
        EMPTY_INFORMATIONAL_HEADERS = new HeadersFuture<>();
        EMPTY_INFORMATIONAL_HEADERS.doComplete(ImmutableList.of());

        EMPTY_TRAILERS = new HeadersFuture<>();
        EMPTY_TRAILERS.doComplete(HttpHeaders.of());
    }

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultHttpResponseBodyStream, HeadersFuture>
            informationalHeadersFutureUpdater = AtomicReferenceFieldUpdater
            .newUpdater(DefaultHttpResponseBodyStream.class, HeadersFuture.class, "informationalHeadersFuture");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultHttpResponseBodyStream, HeadersFuture>
            trailersFutureUpdater = AtomicReferenceFieldUpdater
            .newUpdater(DefaultHttpResponseBodyStream.class, HeadersFuture.class, "trailersFuture");

    private final HeadersFuture<ResponseHeaders> headersFuture = new HeadersFuture<>();
    private final HttpResponse response;

    @Nullable
    private volatile HeadersFuture<List<ResponseHeaders>> informationalHeadersFuture;
    @Nullable
    private volatile HeadersFuture<HttpHeaders> trailersFuture;
    private volatile boolean wroteAny;

    DefaultHttpResponseBodyStream(HttpResponse response) {
        requireNonNull(response, "response");
        this.response = response;
    }

    @Override
    public CompletableFuture<List<ResponseHeaders>> informationalHeaders() {
        final HeadersFuture<List<ResponseHeaders>> informationalHeadersFuture = this.informationalHeadersFuture;
        if (informationalHeadersFuture != null) {
            return informationalHeadersFuture;
        }

        informationalHeadersFutureUpdater.compareAndSet(this, null, new HeadersFuture<>());
        return this.informationalHeadersFuture;
    }

    @Override
    public CompletableFuture<ResponseHeaders> headers() {
        return headersFuture;
    }

    @Override
    public CompletableFuture<HttpHeaders> trailers() {
        final HeadersFuture<HttpHeaders> trailersFuture = this.trailersFuture;
        if (trailersFuture != null) {
            return trailersFuture;
        }

        trailersFutureUpdater.compareAndSet(this, null, new HeadersFuture<>());
        return this.trailersFuture;
    }

    @Override
    public boolean isOpen() {
        return response.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return !isOpen() && !wroteAny;
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return response.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor) {
        response.subscribe(new BodySubscriber(subscriber), executor);
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        response.subscribe(new BodySubscriber(subscriber), executor, options);
    }

    @Override
    public void abort() {
        response.abort();
    }

    @Override
    public void abort(Throwable cause) {
        response.abort(cause);
    }

    private final class BodySubscriber implements Subscriber<HttpObject>, Subscription {

        private final Subscriber<? super HttpData> downstream;
        @Nullable
        private ImmutableList.Builder<ResponseHeaders> informationalHeadersBuilder;

        private boolean sawLeadingHeaders;

        @Nullable
        volatile Subscription upstream;
        private volatile long pendingRequests;
        private volatile boolean cancelCalled;

        BodySubscriber(Subscriber<? super HttpData> downstream) {
            this.downstream = requireNonNull(downstream, "downstream");
            downstream.onSubscribe(this);
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (!upstreamUpdater.compareAndSet(this, null, subscription) || cancelCalled) {
                subscription.cancel();
                return;
            }
            if (pendingRequests != 0) {
                subscription.request(pendingRequests);
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                // Just abort the publisher so subscriber().onError(e) is called and resources are cleaned up.
                response.abort(new IllegalArgumentException(
                        "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
                return;
            }
            final Subscription upstream = this.upstream;
            if (upstream == null) {
                pendingRequests = LongMath.saturatedAdd(n, pendingRequests);
            } else {
                upstream.request(n);
            }
        }

        @Override
        public void cancel() {
            cancelCalled = true;
            maybeCompleteHeaders();
            if (upstream != null) {
                upstream.cancel();
            }
        }

        @Override
        public void onNext(HttpObject httpObject) {
            final Subscription upstream = this.upstream;
            if (httpObject instanceof ResponseHeaders) {
                final ResponseHeaders headers = (ResponseHeaders) httpObject;
                final HttpStatus status = headers.status();
                if (status.isInformational()) {
                    if (!sawLeadingHeaders) {
                        if (informationalHeadersBuilder == null) {
                            informationalHeadersBuilder = ImmutableList.builder();
                        }
                        informationalHeadersBuilder.add(headers);
                    }
                } else {
                    sawLeadingHeaders = true;
                    completeInformationHeaders();
                    completeHeaders(headers);
                }
                upstream.request(1);
                return;
            }

            if (httpObject instanceof HttpHeaders) {
                final HttpHeaders trailers = (HttpHeaders) httpObject;
                completeTrailers(trailers);
                upstream.request(1);
                return;
            }

            assert httpObject instanceof HttpData;
            final HttpData data = (HttpData) httpObject;
            wroteAny = true;
            downstream.onNext(data);
        }

        /**
         * Completes informational headers received so far.
         */
        private void completeInformationHeaders() {
            if (informationalHeadersBuilder == null) {
                if (!informationalHeadersFutureUpdater
                        .compareAndSet(DefaultHttpResponseBodyStream.this, null,
                                       EMPTY_INFORMATIONAL_HEADERS)) {
                    informationalHeadersFuture.doComplete(ImmutableList.of());
                }
            } else {
                informationalHeadersFutureUpdater
                        .compareAndSet(DefaultHttpResponseBodyStream.this, null,
                                       new HeadersFuture<>());
                informationalHeadersFuture.doComplete(informationalHeadersBuilder.build());
            }
        }

        /**
         * Completes the specified non-informational headers.
         */
        private void completeHeaders(ResponseHeaders headers) {
            if (headersFuture.isDone()) {
                return;
            }

            headersFuture.doComplete(headers);
        }

        /**
         * Completes the specified trailers.
         */
        private void completeTrailers(HttpHeaders trailers) {
            final HeadersFuture<HttpHeaders> trailersFuture =
                    DefaultHttpResponseBodyStream.this.trailersFuture;
            if (trailersFuture != null) {
                trailersFuture.doComplete(trailers);
            } else {
                trailersFutureUpdater.compareAndSet(DefaultHttpResponseBodyStream.this,
                                                    null, new HeadersFuture<>());
                DefaultHttpResponseBodyStream.this.trailersFuture.doComplete(trailers);
            }
        }

        @Override
        public void onError(Throwable t) {
            maybeCompleteHeaders();
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            maybeCompleteHeaders();
            downstream.onComplete();
        }

        private void maybeCompleteHeaders() {
            completeInformationHeaders();
            completeHeaders(HEADERS_WITH_UNKNOWN_STATUS);
            if (trailersFuture == null) {
                trailersFutureUpdater.compareAndSet(DefaultHttpResponseBodyStream.this, null, EMPTY_TRAILERS);
            }
        }
    }

    private static final class HeadersFuture<T> extends UnmodifiableFuture<T> {
        @Override
        protected void doComplete(@Nullable T value) {
            super.doComplete(value);
        }
    }
}

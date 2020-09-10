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

import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.util.concurrent.EventExecutor;

public class DefaultSplitHttpResponse implements StreamMessage<HttpData>, SplitHttpResponse {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSplitHttpResponse.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<BodySubscriber, Subscriber> downstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(BodySubscriber.class, Subscriber.class, "downstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultSplitHttpResponse, HeadersFuture>
            informationalHeadersFutureUpdater = AtomicReferenceFieldUpdater
            .newUpdater(DefaultSplitHttpResponse.class, HeadersFuture.class, "informationalHeadersFuture");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultSplitHttpResponse, HeadersFuture>
            trailersFutureUpdater = AtomicReferenceFieldUpdater
            .newUpdater(DefaultSplitHttpResponse.class, HeadersFuture.class, "trailersFuture");

    private static final ResponseHeaders HEADERS_WITH_UNKNOWN_STATUS = ResponseHeaders.of(HttpStatus.UNKNOWN);
    private static final HeadersFuture<List<ResponseHeaders>> EMPTY_INFORMATIONAL_HEADERS;
    private static final HeadersFuture<HttpHeaders> EMPTY_TRAILERS;

    static {
        EMPTY_INFORMATIONAL_HEADERS = new HeadersFuture<>();
        EMPTY_INFORMATIONAL_HEADERS.doComplete(ImmutableList.of());

        EMPTY_TRAILERS = new HeadersFuture<>();
        EMPTY_TRAILERS.doComplete(HttpHeaders.of());
    }

    private final HeadersFuture<ResponseHeaders> headersFuture = new HeadersFuture<>();
    private final BodySubscriber bodySubscriber = new BodySubscriber();
    private final HttpResponse response;
    private final EventExecutor executor;

    @Nullable
    private volatile HeadersFuture<List<ResponseHeaders>> informationalHeadersFuture;
    @Nullable
    private volatile HeadersFuture<HttpHeaders> trailersFuture;
    private volatile boolean wroteAny;

    public DefaultSplitHttpResponse(HttpResponse response, EventExecutor executor,
                                    SubscriptionOption... options) {
        this.response = requireNonNull(response, "response");
        this.executor = requireNonNull(executor, "executor");

        response.subscribe(bodySubscriber, executor, options);
    }

    @Override
    public final CompletableFuture<List<ResponseHeaders>> informationalHeaders() {
        HeadersFuture<List<ResponseHeaders>> informationalHeadersFuture = this.informationalHeadersFuture;
        if (informationalHeadersFuture != null) {
            return informationalHeadersFuture;
        }

        informationalHeadersFuture = new HeadersFuture<>();
        if (informationalHeadersFutureUpdater.compareAndSet(this, null, informationalHeadersFuture)) {
            return informationalHeadersFuture;
        } else {
            return this.informationalHeadersFuture;
        }
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
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor unused) {
        requireNonNull(subscriber, "subscriber");
        if (executor.inEventLoop()) {
            bodySubscriber.setDownStream(subscriber);
        } else {
            executor.execute(() -> bodySubscriber.setDownStream(subscriber));
        }
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... unused) {
        throw new UnsupportedOperationException("Use 'HttpResponse.split(executor, options)' instead.");
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

        @Nullable
        private ImmutableList.Builder<ResponseHeaders> informationalHeadersBuilder;
        @Nullable
        private Throwable cause;

        private boolean completing;
        private boolean sawLeadingHeaders;

        // 1 is used for prefetching headers
        private long pendingRequests = 1;

        @Nullable
        volatile Subscriber<? super HttpData> downstream;
        @Nullable
        private volatile Subscription upstream;

        private volatile boolean cancelCalled;

        private void setDownStream(Subscriber<? super HttpData> downstream) {
            try {
                if (!downstreamUpdater.compareAndSet(this, null, downstream)) {
                    downstream.onSubscribe(NoopSubscription.get());
                    downstream.onError(new IllegalStateException("subscribed by other subscriber already"));
                    return;
                }
                downstream.onSubscribe(this);
                if (cause != null) {
                    downstream.onError(cause);
                } else if (completing) {
                    downstream.onComplete();
                }
            } catch (Throwable t) {
                throwIfFatal(t);
                logger.warn("Subscriber should not throw an exception. subscriber: {}", downstream, t);
            }
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (cancelCalled || upstream != null) {
                subscription.cancel();
                return;
            }
            upstream = subscription;
            subscription.request(pendingRequests);
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                // Just abort the publisher so subscriber().onError(e) is called and resources are cleaned up.
                response.abort(new IllegalArgumentException(
                        "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
                return;
            }
            if (executor.inEventLoop()) {
                request0(n);
            } else {
                executor.execute(() -> request0(n));
            }
        }

        private void request0(long n) {
            final Subscription upstream = this.upstream;
            if (upstream == null) {
                pendingRequests = LongMath.saturatedAdd(n, pendingRequests);
            } else {
                upstream.request(n);
            }
        }

        @Override
        public void cancel() {
            if (cancelCalled) {
                return;
            }
            cancelCalled = true;
            downstream = NoopSubscriber.get();
            maybeCompleteHeaders(null);
            final Subscription upstream = this.upstream;
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
                    upstream.request(1);
                } else {
                    sawLeadingHeaders = true;
                    completeInformationHeaders();
                    headersFuture.doComplete(headers);
                }
                return;
            }

            if (httpObject instanceof HttpHeaders) {
                final HttpHeaders trailers = (HttpHeaders) httpObject;
                completeTrailers(trailers);
                return;
            }

            final Subscriber<? super HttpData> downstream = this.downstream;
            assert downstream != null;
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
                        .compareAndSet(DefaultSplitHttpResponse.this, null,
                                       EMPTY_INFORMATIONAL_HEADERS)) {
                    informationalHeadersFuture.doComplete(ImmutableList.of());
                }
            } else {
                final List<ResponseHeaders> informationalHeaders = informationalHeadersBuilder.build();
                HeadersFuture<List<ResponseHeaders>> headersFuture = informationalHeadersFuture;
                if (headersFuture != null) {
                    headersFuture.doComplete(informationalHeaders);
                    return;
                }

                headersFuture = new HeadersFuture<>();
                if (informationalHeadersFutureUpdater
                        .compareAndSet(DefaultSplitHttpResponse.this, null, headersFuture)) {
                    headersFuture.doComplete(informationalHeaders);
                } else {
                    informationalHeadersFuture.doComplete(informationalHeaders);
                }
            }
        }

        /**
         * Completes the specified trailers.
         */
        private void completeTrailers(HttpHeaders trailers) {
            HeadersFuture<HttpHeaders> trailersFuture = DefaultSplitHttpResponse.this.trailersFuture;
            if (trailersFuture != null) {
                trailersFuture.doComplete(trailers);
                return;
            }

            trailersFuture = new HeadersFuture<>();
            if (trailersFutureUpdater.compareAndSet(DefaultSplitHttpResponse.this, null, trailersFuture)) {
                trailersFuture.doComplete(trailers);
            } else {
                DefaultSplitHttpResponse.this.trailersFuture.doComplete(trailers);
            }
        }

        @Override
        public void onError(Throwable cause) {
            maybeCompleteHeaders(cause);
            final Subscriber<? super HttpData> downstream = this.downstream;
            if (downstream == null) {
                this.cause = cause;
            } else {
                downstream.onError(cause);
            }
        }

        @Override
        public void onComplete() {
            maybeCompleteHeaders(null);
            final Subscriber<? super HttpData> downstream = this.downstream;
            if (downstream == null) {
                completing = true;
            } else {
                downstream.onComplete();
            }
        }

        private void maybeCompleteHeaders(@Nullable Throwable cause) {
            completeInformationHeaders();

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

    private static final class HeadersFuture<T> extends UnmodifiableFuture<T> {
        @Override
        protected void doComplete(@Nullable T value) {
            super.doComplete(value);
        }

        @Override
        protected void doCompleteExceptionally(Throwable cause) {
            super.doCompleteExceptionally(cause);
        }
    }
}

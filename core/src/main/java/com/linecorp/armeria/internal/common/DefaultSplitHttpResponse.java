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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;

public class DefaultSplitHttpResponse implements StreamMessage<HttpData>, SplitHttpResponse {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSplitHttpResponse.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<BodySubscriber, Subscriber> downstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(BodySubscriber.class, Subscriber.class, "downstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultSplitHttpResponse, HeadersFuture>
            trailersFutureUpdater = AtomicReferenceFieldUpdater
            .newUpdater(DefaultSplitHttpResponse.class, HeadersFuture.class, "trailersFuture");

    private static final ResponseHeaders HEADERS_WITH_UNKNOWN_STATUS = ResponseHeaders.of(HttpStatus.UNKNOWN);
    private static final HeadersFuture<HttpHeaders> EMPTY_TRAILERS;
    private static final SubscriptionOption[] EMPTY_OPTIONS = {};

    static {
        EMPTY_TRAILERS = new HeadersFuture<>();
        EMPTY_TRAILERS.doComplete(HttpHeaders.of());
    }

    private final HeadersFuture<ResponseHeaders> headersFuture = new HeadersFuture<>();
    private final BodySubscriber bodySubscriber = new BodySubscriber();
    private final HttpResponse response;
    private final EventExecutor upstreamExecutor;

    @Nullable
    private volatile HeadersFuture<HttpHeaders> trailersFuture;
    private volatile boolean wroteAny;

    public DefaultSplitHttpResponse(HttpResponse response, EventExecutor executor) {
        this.response = requireNonNull(response, "response");
        upstreamExecutor = requireNonNull(executor, "executor");

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
        subscribe(subscriber, executor, EMPTY_OPTIONS);
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

    @Override
    public void abort() {
        response.abort();
    }

    @Override
    public void abort(Throwable cause) {
        response.abort(cause);
    }

    private final class BodySubscriber implements Subscriber<HttpObject>, Subscription {

        private boolean completing;
        // 1 is used for prefetching headers
        private long pendingRequests = 1;

        private volatile boolean notifyCancellation;
        private boolean usePooledObject;

        @Nullable
        volatile Subscriber<? super HttpData> downstream;
        @Nullable
        private volatile Subscription upstream;
        @Nullable
        private volatile EventExecutor executor;
        @Nullable
        private volatile Throwable cause;

        private volatile boolean cancelCalled;

        private void initDownstream(Subscriber<? super HttpData> downstream, EventExecutor executor,
                                    SubscriptionOption... options) {
            assert executor.inEventLoop();

            this.executor = executor;
            for (SubscriptionOption option : options) {
                if (option == SubscriptionOption.NOTIFY_CANCELLATION) {
                    notifyCancellation = true;
                } else if (option == SubscriptionOption.WITH_POOLED_OBJECTS) {
                    usePooledObject = true;
                }
            }

            try {
                downstream.onSubscribe(this);
                final Throwable cause = this.cause;
                if (cause != null) {
                    onError0(cause, downstream);
                } else if (completing) {
                    onComplete0(downstream);
                }
            } catch (Throwable t) {
                throwIfFatal(t);
                logger.warn("Subscriber should not throw an exception. subscriber: {}", downstream, t);
            }
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (upstream != null) {
                subscription.cancel();
                return;
            }
            upstream = subscription;
            if (cancelCalled) {
                subscription.cancel();
                return;
            }
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
            if (upstreamExecutor.inEventLoop()) {
                request0(n);
            } else {
                upstreamExecutor.execute(() -> request0(n));
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
            if (!notifyCancellation) {
                downstream = NoopSubscriber.get();
            }
            maybeCompleteHeaders(null);
            final Subscription upstream = this.upstream;
            if (upstream != null) {
                upstream.cancel();
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

            if (httpObject instanceof HttpHeaders) {
                final HttpHeaders trailers = (HttpHeaders) httpObject;
                completeTrailers(trailers);
                return;
            }

            final Subscriber<? super HttpData> downstream = this.downstream;
            assert downstream != null;
            assert httpObject instanceof HttpData;

            final EventExecutor executor = this.executor;
            if (executor.inEventLoop()) {
                onNext0((HttpData) httpObject);
            } else {
                executor.execute(() -> onNext0((HttpData) httpObject));
            }
        }

        private void onNext0(HttpData httpData) {
            wroteAny = true;
            if (!usePooledObject) {
                httpData = PooledObjects.copyAndClose(httpData);
            }
            downstream.onNext(httpData);
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
            final EventExecutor executor = this.executor;
            final Subscriber<? super HttpData> downstream = this.downstream;
            if (executor == null || downstream == null) {
                this.cause = cause;
                return;
            }

            if (executor.inEventLoop()) {
                onError0(cause, downstream);
            } else {
                executor.execute(() -> onError0(cause, downstream));
            }
        }

        private void onError0(Throwable cause, Subscriber<? super HttpData> downstream) {
            downstream.onError(cause);
            this.downstream = NoopSubscriber.get();
        }

        @Override
        public void onComplete() {
            maybeCompleteHeaders(null);
            final EventExecutor executor = this.executor;
            final Subscriber<? super HttpData> downstream = this.downstream;

            if (executor == null || downstream == null) {
                completing = true;
                return;
            }

            if (executor.inEventLoop()) {
                onComplete0(downstream);
            } else {
                executor.execute(() -> onComplete0(downstream));
            }
        }

        private void onComplete0(Subscriber<? super HttpData> downstream) {
            downstream.onComplete();
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

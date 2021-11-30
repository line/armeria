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

import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMessage;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.SplitHttpMessage;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;

abstract class AbstractSplitHttpMessage implements SplitHttpMessage, StreamMessage<HttpData> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractSplitHttpMessage.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<BodySubscriber, Subscriber>
            downstreamUpdater = AtomicReferenceFieldUpdater.newUpdater(BodySubscriber.class, Subscriber.class,
                                                                       "downstream");

    @SuppressWarnings("rawtypes")
    protected static final AtomicReferenceFieldUpdater<AbstractSplitHttpMessage, HeadersFuture>
            trailersFutureUpdater = AtomicReferenceFieldUpdater.newUpdater(AbstractSplitHttpMessage.class,
                                                                           HeadersFuture.class,
                                                                           "trailersFuture");

    private final HttpMessage upstream;
    protected final EventExecutor upstreamExecutor;

    @Nullable
    protected volatile HeadersFuture<HttpHeaders> trailersFuture;

    private volatile boolean wroteAny;

    protected AbstractSplitHttpMessage(HttpMessage upstream, EventExecutor executor) {
        this.upstream = requireNonNull(upstream, "upstream");
        upstreamExecutor = requireNonNull(executor, "executor");
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
    public final boolean isOpen() {
        return upstream.isOpen();
    }

    @Override
    public final boolean isEmpty() {
        return !isOpen() && !wroteAny;
    }

    @Override
    public final long demand() {
        return upstream.demand();
    }

    @Override
    public final CompletableFuture<Void> whenComplete() {
        return upstream.whenComplete();
    }

    @Override
    public final void abort() {
        upstream.abort();
    }

    @Override
    public final void abort(Throwable cause) {
        upstream.abort(cause);
    }

    @Override
    public final EventExecutor defaultSubscriberExecutor() {
        return upstreamExecutor;
    }

    protected final void subscribe0(Subscriber<? super HttpData> subscriber, BodySubscriber bodySubscriber,
                                    EventExecutor executor, SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(bodySubscriber, "bodySubscriber");
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

    protected abstract class BodySubscriber implements Subscriber<HttpObject>, Subscription {

        // 1 is used for prefetching headers
        private long pendingRequests;

        private boolean completing;

        protected volatile boolean notifyCancellation;
        private boolean usePooledObject;

        @Nullable
        protected volatile Subscriber<? super HttpData> downstream;

        @Nullable
        protected volatile Subscription upstream;

        @Nullable
        private volatile EventExecutor executor;

        @Nullable
        private volatile Throwable cause;

        protected volatile boolean cancelCalled;

        protected BodySubscriber(int prefetch) {
            pendingRequests = prefetch;
        }

        protected void initDownstream(Subscriber<? super HttpData> downstream, EventExecutor executor,
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
            }
            if (pendingRequests > 0) {
                subscription.request(pendingRequests);
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                AbstractSplitHttpMessage.this.upstream.abort(new IllegalArgumentException(
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
        public final void cancel() {
            if (cancelCalled) {
                return;
            }
            cancelCalled = true;
            if (!notifyCancellation) {
                downstream = NoopSubscriber.get();
            }
            completeOnSubscriptionCancel();
            final Subscription upstream = this.upstream;
            if (upstream != null) {
                upstream.cancel();
            }
        }

        protected abstract void completeOnSubscriptionCancel();

        @Override
        public void onNext(HttpObject httpObject) {
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

        @Override
        public void onComplete() {
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

        @Override
        public void onError(Throwable cause) {
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

        /**
         * Completes the specified trailers.
         */
        protected final void completeTrailers(HttpHeaders trailers) {
            HeadersFuture<HttpHeaders> trailersFuture = AbstractSplitHttpMessage.this.trailersFuture;
            if (trailersFuture != null) {
                trailersFuture.doComplete(trailers);
                return;
            }

            trailersFuture = new HeadersFuture<>();
            if (trailersFutureUpdater.compareAndSet(AbstractSplitHttpMessage.this, null, trailersFuture)) {
                trailersFuture.doComplete(trailers);
            } else {
                AbstractSplitHttpMessage.this.trailersFuture.doComplete(trailers);
            }
        }
    }

    static final class HeadersFuture<T> extends UnmodifiableFuture<T> {

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

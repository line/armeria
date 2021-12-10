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
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;

class BodySubscriber implements Subscriber<HttpObject>, Subscription {

    private static final Logger logger = LoggerFactory.getLogger(BodySubscriber.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<BodySubscriber, HeadersFuture>
            trailersFutureUpdater = AtomicReferenceFieldUpdater.newUpdater(BodySubscriber.class,
                                                                           HeadersFuture.class,
                                                                           "trailersFuture");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<BodySubscriber, Subscriber>
            downstreamUpdater = AtomicReferenceFieldUpdater.newUpdater(BodySubscriber.class, Subscriber.class,
                                                                       "downstream");

    private static final HeadersFuture<HttpHeaders> EMPTY_TRAILERS_FUTURE;

    static {
        EMPTY_TRAILERS_FUTURE = new HeadersFuture<>();
        EMPTY_TRAILERS_FUTURE.doComplete(HttpHeaders.of());
    }

    private final HttpMessage upstreamMessage;
    private final EventExecutor upstreamExecutor;

    // 1 is used for prefetching headers
    private long pendingRequests;

    private boolean completing;

    private volatile boolean notifyCancellation;
    private boolean usePooledObject;

    @Nullable
    private volatile HeadersFuture<HttpHeaders> trailersFuture;

    private volatile boolean wroteAny;

    @Nullable
    private volatile Subscriber<? super HttpData> downstream;

    @Nullable
    private volatile Subscription upstream;

    @Nullable
    private volatile EventExecutor executor;

    @Nullable
    private volatile Throwable cause;

    private volatile boolean cancelCalled;

    protected BodySubscriber(int prefetch, HttpMessage upstreamMessage, EventExecutor upstreamExecutor) {
        pendingRequests = prefetch;
        this.upstreamMessage = requireNonNull(upstreamMessage, "upstreamMessage");
        this.upstreamExecutor = requireNonNull(upstreamExecutor, "upstreamExecutor");
    }

    @SuppressWarnings("rawtypes")
    public static AtomicReferenceFieldUpdater<BodySubscriber, Subscriber> downstreamUpdater() {
        return downstreamUpdater;
    }

    public CompletableFuture<HttpHeaders> trailersFuture() {
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

    public boolean wroteAny() {
        return wroteAny;
    }

    @Nullable
    public final Subscription upstream() {
        return upstream;
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
            return;
        }
        if (pendingRequests > 0) {
            subscription.request(pendingRequests);
        }
    }

    @Override
    public void request(long n) {
        if (n <= 0) {
            upstreamMessage.abort(new IllegalArgumentException(
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
        completeTrailers(HttpHeaders.of());
        final Subscription upstream = this.upstream;
        if (upstream != null) {
            upstream.cancel();
        }
    }

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

    /**
     * Completes the specified trailers.
     */
    private void completeTrailers(HttpHeaders trailers) {
        HeadersFuture<HttpHeaders> trailersFuture = this.trailersFuture;
        if (trailersFuture != null) {
            trailersFuture.doComplete(trailers);
            return;
        }

        trailersFuture = new HeadersFuture<>();
        if (trailersFutureUpdater.compareAndSet(this, null, trailersFuture)) {
            trailersFuture.doComplete(trailers);
        } else {
            this.trailersFuture.doComplete(trailers);
        }
    }

    protected void maybeCompleteHeaders(@Nullable Throwable cause) {
        if (trailersFuture == null) {
            if (trailersFutureUpdater.compareAndSet(this, null, EMPTY_TRAILERS_FUTURE)) {
                return;
            }
        }

        final HeadersFuture<HttpHeaders> trailersFuture = this.trailersFuture;
        assert trailersFuture != null;
        trailersFuture.doComplete(HttpHeaders.of());
    }
}

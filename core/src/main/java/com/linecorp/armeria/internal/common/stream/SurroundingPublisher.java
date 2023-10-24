/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.common.stream;

import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsNotifyCancellation;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;

import io.netty.util.concurrent.EventExecutor;

public final class SurroundingPublisher<T> implements StreamMessage<T> {

    private static final Logger logger = LoggerFactory.getLogger(SurroundingPublisher.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<SurroundingPublisher> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(SurroundingPublisher.class, "subscribed");

    @Nullable
    private final T head;
    private final StreamMessage<T> publisher;
    private final Function<@Nullable Throwable, ? extends @Nullable T> finalizer;

    private volatile int subscribed;
    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    @Nullable
    private volatile SurroundingSubscriber<T> surroundingSubscriber;

    public SurroundingPublisher(@Nullable T head, Publisher<? extends T> publisher,
                                Function<@Nullable Throwable, ? extends @Nullable T> finalizer) {
        requireNonNull(publisher, "publisher");
        requireNonNull(finalizer, "finalizer");
        this.head = head;
        if (publisher instanceof StreamMessage) {
            //noinspection unchecked
            this.publisher = (StreamMessage<T>) publisher;
        } else {
            this.publisher = new PublisherBasedStreamMessage<>(publisher);
        }
        this.finalizer = finalizer;
    }

    @Override
    public boolean isOpen() {
        return !completionFuture.isDone();
    }

    @Override
    public boolean isEmpty() {
        if (isOpen()) {
            return false;
        }
        final SurroundingSubscriber<T> surroundingSubscriber = this.surroundingSubscriber;
        return surroundingSubscriber == null || !surroundingSubscriber.publishedAny;
    }

    @Override
    public long demand() {
        final SurroundingSubscriber<T> surroundingSubscriber = this.surroundingSubscriber;
        if (surroundingSubscriber != null) {
            return surroundingSubscriber.requested;
        } else {
            return 0;
        }
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return completionFuture;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        if (!subscribedUpdater.compareAndSet(this, 0, 1)) {
            subscriber.onSubscribe(NoopSubscription.get());
            if (completionFuture.isCompletedExceptionally()) {
                completionFuture.exceptionally(cause -> {
                    subscriber.onError(cause);
                    return null;
                });
            } else {
                subscriber.onError(new IllegalStateException("Only single subscriber is allowed!"));
            }
            return;
        }

        if (executor.inEventLoop()) {
            subscribe0(subscriber, executor, options);
        } else {
            executor.execute(() -> subscribe0(subscriber, executor, options));
        }
    }

    private void subscribe0(Subscriber<? super T> subscriber, EventExecutor executor,
                            SubscriptionOption... options) {

        final SurroundingSubscriber<T> surroundingSubscriber = new SurroundingSubscriber<>(
                head, publisher, finalizer, subscriber, executor, completionFuture, options);
        this.surroundingSubscriber = surroundingSubscriber;
        subscriber.onSubscribe(surroundingSubscriber);

        // To make sure to close the SurroundingSubscriber when this is aborted.
        if (completionFuture.isCompletedExceptionally()) {
            completionFuture.exceptionally(cause -> {
                surroundingSubscriber.close(cause);
                return null;
            });
        }
    }

    @Override
    public void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");

        // `completionFuture` should be set before `SurroundingSubscriber` publishes data
        // to guarantee the visibility of the abortion `cause` after
        // SurroundingSubscriber is set in `subscriber0()`.
        completionFuture.completeExceptionally(cause);

        if (subscribedUpdater.compareAndSet(this, 0, 1)) {
            publisher.abort(cause);
            if (head != null) {
                StreamMessageUtil.closeOrAbort(head, cause);
            }
            return;
        }

        final SurroundingSubscriber<T> surroundingSubscriber = this.surroundingSubscriber;
        if (surroundingSubscriber != null) {
            surroundingSubscriber.close(cause);
        }
    }

    private static final class SurroundingSubscriber<T> implements Subscriber<T>, Subscription {

        enum State {
            REQUIRE_HEAD,
            REQUIRE_BODY,
            REQUIRE_TAIL,
            DONE,
        }

        private State state;

        @Nullable
        private T head;
        private final StreamMessage<T> publisher;
        private final Function<@Nullable Throwable, ? extends @Nullable T> finalizer;

        private Subscriber<? super T> downstream;
        private final EventExecutor executor;
        @Nullable
        private volatile Subscription upstream;

        private long requested;
        private long upstreamRequested;
        private boolean subscribed;
        private volatile boolean publishedAny;

        private final CompletableFuture<Void> completionFuture;
        private final SubscriptionOption[] options;

        SurroundingSubscriber(@Nullable T head, StreamMessage<T> publisher,
                              Function<@Nullable Throwable, ? extends @Nullable T> finalizer,
                              Subscriber<? super T> downstream, EventExecutor executor,
                              CompletableFuture<Void> completionFuture, SubscriptionOption... options) {
            requireNonNull(publisher, "publisher");
            requireNonNull(downstream, "downstream");
            requireNonNull(executor, "executor");
            state = head != null ? State.REQUIRE_HEAD : State.REQUIRE_BODY;
            this.head = head;
            this.publisher = publisher;
            this.finalizer = finalizer;
            this.downstream = downstream;
            this.executor = executor;
            this.completionFuture = completionFuture;
            this.options = options;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                close(new IllegalArgumentException("non-positive request signals are illegal"));
                return;
            }
            if (executor.inEventLoop()) {
                request0(n);
            } else {
                executor.execute(() -> request0(n));
            }
        }

        private void request0(long n) {
            if (state == State.DONE) {
                return;
            }

            final long oldRequested = requested;
            if (oldRequested == Long.MAX_VALUE) {
                return;
            }
            if (n == Long.MAX_VALUE) {
                requested = Long.MAX_VALUE;
            } else {
                requested = LongMath.saturatedAdd(oldRequested, n);
            }

            if (oldRequested > 0) {
                // SurroundingSubscriber is publishing data.
                // New requests will be handled by 'publishDownstream(item)'.
                return;
            }

            publish();
        }

        private void publish() {
            if (state == State.DONE || requested <= 0 && upstreamRequested <= 0) {
                return;
            }

            switch (state) {
                case REQUIRE_HEAD: {
                    sendHead();
                    break;
                }
                case REQUIRE_BODY: {
                    if (!subscribed) {
                        subscribed = true;
                        publisher.subscribe(this, executor, options);
                        return;
                    }
                    if (upstreamRequested > 0) {
                        return;
                    }
                    final Subscription upstream = this.upstream;
                    if (upstream != null) {
                        requestUpstream(upstream);
                    }
                    break;
                }
                case REQUIRE_TAIL: {
                    sendTail();
                    break;
                }
            }
        }

        private void sendHead() {
            setState(State.REQUIRE_HEAD, State.REQUIRE_BODY);
            assert head != null;
            final T head = this.head;
            this.head = null;
            publishDownstream(head, true);
        }

        private void sendTail() {
            assert state == State.REQUIRE_TAIL;
            finalize(null);
        }

        private void finalize(@Nullable Throwable cause) {
            final T tail;
            try {
                tail = finalizer.apply(cause);
            } catch (Throwable ex) {
                if (cause != null) {
                    logger.warn("Unexpected exception from finalizer:", ex);
                    close0(cause);
                } else {
                    close0(ex);
                }
                return;
            }

            if (tail == null) {
                // Immediately close the stream if the finalizer returns null.
                close0(cause);
            } else {
                downstream.onNext(tail);
                close0(null);
            }
        }

        private void requestUpstream(Subscription subscription) {
            if (requested <= 0) {
                return;
            }
            assert upstreamRequested == 0;
            upstreamRequested = requested;
            if (requested < Long.MAX_VALUE) {
                requested = 0;
            }
            subscription.request(upstreamRequested);
        }

        private void publishDownstream(T item, boolean head) {
            requireNonNull(item, "item");
            if (state == State.DONE) {
                StreamMessageUtil.closeOrAbort(item);
                return;
            }
            downstream.onNext(item);

            if (head) {
                if (requested < Long.MAX_VALUE) {
                    requested--;
                }
                subscribed = true;
                publisher.subscribe(this, executor, options);
            } else {
                assert upstreamRequested > 0;
                if (upstreamRequested < Long.MAX_VALUE) {
                    upstreamRequested--;
                }
            }

            if (!publishedAny) {
                publishedAny = true;
            }

            publish();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (state == State.DONE) {
                subscription.cancel();
                return;
            }
            upstream = subscription;
            requestUpstream(subscription);
        }

        @Override
        public void onNext(T item) {
            requireNonNull(item, "item");
            publishDownstream(item, false);
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");
            finalize(cause);
        }

        @Override
        public void onComplete() {
            if (state == State.DONE) {
                return;
            }
            setState(State.REQUIRE_BODY, State.REQUIRE_TAIL);
            publish();
        }

        @Override
        public void cancel() {
            if (executor.inEventLoop()) {
                cancel0();
            } else {
                executor.execute(this::cancel0);
            }
        }

        private void cancel0() {
            if (state == State.DONE) {
                return;
            }
            state = State.DONE;

            final Subscription upstream = this.upstream;
            if (upstream != null) {
                upstream.cancel();
            }
            final CancelledSubscriptionException cause = CancelledSubscriptionException.get();
            if (containsNotifyCancellation(options)) {
                downstream.onError(cause);
            }
            downstream = NoopSubscriber.get();
            completionFuture.completeExceptionally(cause);
            release(null);
        }

        private void close(@Nullable Throwable cause) {
            if (executor.inEventLoop()) {
                close0(cause);
            } else {
                executor.execute(() -> close0(cause));
            }
        }

        private void close0(@Nullable Throwable cause) {
            if (state == State.DONE) {
                return;
            }
            state = State.DONE;

            if (cause == null) {
                downstream.onComplete();
                completionFuture.complete(null);
            } else {
                final Subscription upstream = this.upstream;
                if (upstream != null) {
                    upstream.cancel();
                }
                downstream.onError(cause);
                completionFuture.completeExceptionally(cause);
            }
            release(cause);
        }

        private void release(@Nullable Throwable cause) {
            if (head != null) {
                StreamMessageUtil.closeOrAbort(head, cause);
            }
        }

        private void setState(State oldState, State newState) {
            assert state == oldState
                    : "curState: " + state + ", oldState: " + oldState + ", newState: " + newState;
            assert newState != State.REQUIRE_HEAD : "oldState: " + oldState + ", newState: " + newState;
            state = newState;
        }
    }
}

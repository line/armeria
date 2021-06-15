/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import static com.linecorp.armeria.common.stream.SubscriberUtil.abortedOrLate;
import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsWithPooledObjects;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * Adapts a {@link Publisher} into a {@link StreamMessage}.
 *
 * <p>Note that the elements in the {@link Publisher} are not released when {@link Subscription#cancel()} or
 * {@link #abort()} is called. So you should add a hook in order to release the elements. You can use
 * <a href="https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html#doOnDiscard-java.lang.Class-java.util.function.Consumer-">doOnDiscard</a>
 * if you are using Reactor, or you can use
 * <a href="http://reactivex.io/RxJava/3.x/javadoc/io/reactivex/rxjava3/core/Observable.html#doOnDispose-io.reactivex.rxjava3.functions.Action-">doOnDispose</a>
 * if you are using RxJava.
 *
 * @param <T> the type of element signaled
 */
@UnstableApi
public class PublisherBasedStreamMessage<T> implements StreamMessage<T> {

    private static final Logger logger = LoggerFactory.getLogger(PublisherBasedStreamMessage.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<PublisherBasedStreamMessage, AbortableSubscriber>
            subscriberUpdater = AtomicReferenceFieldUpdater.newUpdater(
            PublisherBasedStreamMessage.class, AbortableSubscriber.class, "subscriber");

    private final Publisher<? extends T> publisher;
    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    @Nullable // Updated only via subscriberUpdater.
    private volatile AbortableSubscriber subscriber;
    private volatile boolean publishedAny;
    private volatile long demand;

    /**
     * Creates a new instance with the specified delegate {@link Publisher}.
     */
    public PublisherBasedStreamMessage(Publisher<? extends T> publisher) {
        this.publisher = requireNonNull(publisher, "publisher");
    }

    /**
     * Returns the delegate {@link Publisher}.
     */
    protected final Publisher<? extends T> delegate() {
        return publisher;
    }

    @Override
    public final boolean isOpen() {
        return !completionFuture.isDone();
    }

    @Override
    public final boolean isEmpty() {
        return !isOpen() && !publishedAny;
    }

    @Override
    public final long demand() {
        return demand;
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        subscribe0(subscriber, executor, false, false);
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                                SubscriptionOption... options) {
        requireNonNull(options, "options");
        final boolean withPooledObjects = containsWithPooledObjects(options);
        final boolean notifyCancellation = containsNotifyCancellation(options);
        subscribe0(subscriber, executor, withPooledObjects, notifyCancellation);
    }

    private void subscribe0(Subscriber<? super T> subscriber, EventExecutor executor,
                            boolean withPooledObjects, boolean notifyCancellation) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");

        if (!subscribe1(subscriber, executor, withPooledObjects, notifyCancellation)) {
            final AbortableSubscriber oldSubscriber = this.subscriber;
            assert oldSubscriber != null;
            failLateSubscriber(executor, subscriber, oldSubscriber.subscriber);
        }
    }

    private boolean subscribe1(Subscriber<? super T> subscriber, EventExecutor executor,
                               boolean withPooledObjects, boolean notifyCancellation) {
        final AbortableSubscriber s =
                new AbortableSubscriber(this, subscriber, executor, withPooledObjects, notifyCancellation);
        if (!subscriberUpdater.compareAndSet(this, null, s)) {
            return false;
        }

        if (publisher instanceof StreamMessage) {
            //noinspection unchecked
            ((StreamMessage<? extends T>) publisher).subscribe(s, executor);
        } else {
            publisher.subscribe(s);
        }

        return true;
    }

    private static void failLateSubscriber(EventExecutor executor,
                                           Subscriber<?> lateSubscriber, Subscriber<?> oldSubscriber) {
        final Throwable cause = abortedOrLate(oldSubscriber);

        executor.execute(() -> {
            try {
                lateSubscriber.onSubscribe(NoopSubscription.get());
                lateSubscriber.onError(cause);
            } catch (Throwable t) {
                throwIfFatal(t);
                logger.warn("Subscriber should not throw an exception. subscriber: {}", lateSubscriber, t);
            }
        });
    }

    @Override
    public final void abort() {
        abort0(AbortedStreamException.get());
    }

    @Override
    public final void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        abort0(cause);
    }

    private void abort0(Throwable cause) {
        final AbortableSubscriber subscriber = this.subscriber;
        if (subscriber != null) {
            subscriber.abort(cause);
            return;
        }

        final AbortableSubscriber abortable = new AbortableSubscriber(this, AbortingSubscriber.get(cause),
                                                                      ImmediateEventExecutor.INSTANCE,
                                                                      false, false);
        if (!subscriberUpdater.compareAndSet(this, null, abortable)) {
            this.subscriber.abort(cause);
            return;
        }

        abortable.abort(cause);
        publisher.subscribe(abortable);
    }

    @Override
    public CompletableFuture<List<T>> collect(EventExecutor executor, SubscriptionOption... options) {
        if (publisher instanceof StreamMessage) {
            //noinspection unchecked
            return ((StreamMessage<T>) publisher).collect(executor, options);
        } else {
            return StreamMessage.super.collect(executor, options);
        }
    }

    @Override
    public final CompletableFuture<Void> whenComplete() {
        return completionFuture;
    }

    @VisibleForTesting
    static final class AbortableSubscriber implements Subscriber<Object>, Subscription {
        private final PublisherBasedStreamMessage<?> parent;
        private final EventExecutor executor;
        private boolean withPooledObjects;
        private final boolean notifyCancellation;
        private Subscriber<Object> subscriber;
        @Nullable
        private volatile Subscription subscription;
        @Nullable
        private volatile Throwable abortCause;

        @SuppressWarnings("unchecked")
        AbortableSubscriber(PublisherBasedStreamMessage<?> parent, Subscriber<?> subscriber,
                            EventExecutor executor, boolean withPooledObjects, boolean notifyCancellation) {
            this.parent = parent;
            this.subscriber = (Subscriber<Object>) subscriber;
            this.executor = executor;
            this.withPooledObjects = withPooledObjects;
            this.notifyCancellation = notifyCancellation;
        }

        @Override
        public void request(long n) {
            final Subscription subscription = this.subscription;
            assert subscription != null;

            if (executor.inEventLoop()) {
                increaseDemand(n);
            } else {
                executor.execute(() -> increaseDemand(n));
            }
            subscription.request(n);
        }

        private void increaseDemand(long n)  {
            parent.demand = LongMath.saturatedAdd(parent.demand, n);
        }

        @Override
        public void cancel() {
            // 'subscription' can never be null here because 'subscriber.onSubscriber()' is invoked
            // only after 'subscription' is set. See onSubscribe0().
            assert subscription != null;

            // Don't cancel but just abort if abort is pending.
            cancelOrAbort(abortCause == null);
        }

        void abort(Throwable cause) {
            if (abortCause == null) {
                abortCause = cause;
            }
            if (subscription != null) {
                cancelOrAbort(false);
            }
        }

        private void cancelOrAbort(boolean cancel) {
            if (executor.inEventLoop()) {
                cancelOrAbort0(cancel);
            } else {
                executor.execute(() -> cancelOrAbort0(cancel));
            }
        }

        private void cancelOrAbort0(boolean cancel) {
            final CompletableFuture<Void> completionFuture = parent.whenComplete();
            if (completionFuture.isDone()) {
                return;
            }

            final Subscriber<Object> subscriber = this.subscriber;
            // Replace the subscriber with a placeholder so that it can be garbage-collected and
            // we conform to the Reactive Streams specification rule 3.13.
            if (!(subscriber instanceof AbortingSubscriber)) {
                this.subscriber = NoopSubscriber.get();
            }

            final Throwable cause = cancel ? CancelledSubscriptionException.get() : abortCause;
            assert cause != null;
            try {
                if (!cancel || notifyCancellation) {
                    subscriber.onError(cause);
                }
                completionFuture.completeExceptionally(cause);
            } catch (Throwable t) {
                final Exception composite = new CompositeException(t, cause);
                completionFuture.completeExceptionally(composite);
                throwIfFatal(t);
                logger.warn("Subscriber.onError() should not raise an exception. subscriber: {}",
                            subscriber, composite);
            } finally {
                subscription.cancel();
            }
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (executor.inEventLoop()) {
                onSubscribe0(subscription);
            } else {
                executor.execute(() -> onSubscribe0(subscription));
            }
        }

        private void onSubscribe0(Subscription subscription) {
            try {
                this.subscription = subscription;
                subscriber.onSubscribe(this);
                if (abortCause != null) {
                    cancelOrAbort0(false);
                }
            } catch (Throwable t) {
                abort(t);
                throwIfFatal(t);
                logger.warn("Subscriber.onSubscribe() should not raise an exception. subscriber: {}",
                            subscriber, t);
            }
        }

        @Override
        public void onNext(Object obj) {
            requireNonNull(obj, "obj");
            parent.publishedAny = true;
            if (executor.inEventLoop()) {
                onNext0(obj);
            } else {
                executor.execute(() -> onNext0(obj));
            }
        }

        private void onNext0(Object obj) {
            if (parent.demand != Long.MAX_VALUE) {
                parent.demand--;
            }
            try {
                if (!withPooledObjects) {
                    obj = PooledObjects.copyAndClose(obj);
                }
                subscriber.onNext(obj);
            } catch (Throwable t) {
                abort(t);
                throwIfFatal(t);
                logger.warn("Subscriber.onNext({}) should not raise an exception. subscriber: {}",
                            obj, subscriber, t);
            }
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");
            if (executor.inEventLoop()) {
                onError0(cause);
            } else {
                executor.execute(() -> onError0(cause));
            }
        }

        private void onError0(Throwable cause) {
            try {
                subscriber.onError(cause);
                parent.whenComplete().completeExceptionally(cause);
            } catch (Throwable t) {
                final Exception composite = new CompositeException(t, cause);
                parent.whenComplete().completeExceptionally(composite);
                throwIfFatal(t);
                logger.warn("Subscriber.onError() should not raise an exception. subscriber: {}",
                            subscriber, composite);
            }
        }

        @Override
        public void onComplete() {
            if (executor.inEventLoop()) {
                onComplete0();
            } else {
                executor.execute(this::onComplete0);
            }
        }

        private void onComplete0() {
            try {
                subscriber.onComplete();
                parent.whenComplete().complete(null);
            } catch (Throwable t) {
                parent.whenComplete().completeExceptionally(t);
                throwIfFatal(t);
                logger.warn("Subscriber.onComplete() should not raise an exception. subscriber: {}",
                            subscriber, t);
            }
        }
    }
}

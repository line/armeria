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

import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.internal.common.stream.SubscriberUtil.abortedOrLate;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.stream.AbortingSubscriber;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A {@link StreamMessage} whose stream is published later by another {@link StreamMessage}. It is useful when
 * your {@link StreamMessage} will not be instantiated early.
 *
 * @param <T> the type of element signaled
 */
@UnstableApi
public class DeferredStreamMessage<T> extends CancellableStreamMessage<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, SubscriptionImpl>
            downstreamSubscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DeferredStreamMessage.class, SubscriptionImpl.class, "downstreamSubscription");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, StreamMessage> upstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    DeferredStreamMessage.class, StreamMessage.class, "upstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<DeferredStreamMessage>
            subscribedToUpstreamUpdater =
            AtomicIntegerFieldUpdater.newUpdater(
                    DeferredStreamMessage.class, "subscribedToUpstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, Throwable>
            abortCauseUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DeferredStreamMessage.class, Throwable.class, "abortCause");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, CompletableFuture>
            collectingFutureUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DeferredStreamMessage.class, CompletableFuture.class, "collectingFuture");

    private static final CompletableFuture<List<?>> NO_COLLECTING_FUTURE =
            UnmodifiableFuture.completedFuture(null);
    private static final SubscriptionImpl NOOP_SUBSCRIPTION = noopSubscription();
    @Nullable
    private final EventExecutor defaultSubscriberExecutor;

    @Nullable
    @SuppressWarnings("unused") // Updated only via upstreamUpdater
    private volatile StreamMessage<T> upstream;

    // Only accessed from subscription's executor.
    @Nullable
    private Subscription upstreamSubscription;

    @Nullable
    @SuppressWarnings("unused") // Updated only via downstreamSubscriptionUpdater
    private volatile SubscriptionImpl downstreamSubscription;

    // Updated only via collectingFutureUpdater
    @Nullable
    private volatile CompletableFuture<List<T>> collectingFuture;

    @Nullable
    private SubscriptionOption[] collectionOptions;
    @Nullable
    private EventExecutor collectingExecutor;

    @SuppressWarnings("unused") // Updated only via subscribedToUpstreamUpdater
    private volatile int subscribedToUpstream;

    // Only accessed from subscription's executor.
    private long pendingDemand;

    @Nullable
    private volatile Throwable abortCause;

    // Only accessed from downstreamSubscription's executor.
    private boolean downstreamOnSubscribeCalled;

    /**
     * Creates a new instance.
     */
    public DeferredStreamMessage() {
        defaultSubscriberExecutor = null;
    }

    /**
     * Creates a new instance.
     */
    public DeferredStreamMessage(EventExecutor defaultSubscriberExecutor) {
        this.defaultSubscriberExecutor = requireNonNull(defaultSubscriberExecutor, "defaultSubscriberExecutor");
    }

    @Override
    public EventExecutor defaultSubscriberExecutor() {
        if (defaultSubscriberExecutor != null) {
            return defaultSubscriberExecutor;
        }
        return super.defaultSubscriberExecutor();
    }

    /**
     * Delegates when the specified {@link CompletionStage} is complete.
     */
    protected final void delegateOnCompletion(CompletionStage<? extends Publisher<T>> stage) {
        requireNonNull(stage, "stage");
        stage.handle((upstream, thrown) -> {
            if (thrown != null) {
                close(Exceptions.peel(thrown));
            } else if (upstream == null) {
                close(new NullPointerException("upstream stage produced a null stream message: " + stage));
            } else {
                delegate(StreamMessage.of(upstream));
            }
            return null;
        });
    }

    /**
     * Sets the upstream {@link StreamMessage} which will actually publish the stream.
     *
     * @throws IllegalStateException if the upstream has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    protected final void delegate(StreamMessage<T> upstream) {
        requireNonNull(upstream, "upstream");

        if (!upstreamUpdater.compareAndSet(this, null, upstream)) {
            final IllegalStateException exception = new IllegalStateException("upstream set already");
            upstream.abort(exception);
            throw exception;
        }

        final Throwable abortCause = this.abortCause;
        if (abortCause != null) {
            upstream.abort(abortCause);
        }

        if (!collectingFutureUpdater.compareAndSet(this, null, NO_COLLECTING_FUTURE)) {
            upstream.collect(collectingExecutor, collectionOptions).handle((result, cause) -> {
                final CompletableFuture<List<T>> collectingFuture = this.collectingFuture;
                assert collectingFuture != null;
                if (cause != null) {
                    collectingFuture.completeExceptionally(cause);
                } else {
                    // `collectingFuture` can be completed exceptionally by `abort()`
                    if (!collectingFuture.complete(result)) {
                        for (final T obj : result) {
                            PooledObjects.close(obj);
                        }
                    }
                }
                return null;
            });
        }

        if (!whenComplete().isDone()) {
            upstream.whenComplete().handle((unused, cause) -> {
                if (cause == null) {
                    whenComplete().complete(null);
                } else {
                    whenComplete().completeExceptionally(cause);
                }
                return null;
            }).exceptionally(CompletionActions::log);
        }

        safeOnSubscribeToUpstream();
    }

    /**
     * Closes the deferred stream without setting a delegate.
     *
     * @throws IllegalStateException if the upstream has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    public final void close() {
        delegate(StreamMessage.of());
    }

    /**
     * Closes the deferred stream without setting a delegate.
     *
     * @throws IllegalStateException if the delegate has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    public final void close(Throwable cause) {
        requireNonNull(cause, "cause");
        delegate(StreamMessage.aborted(cause));
    }

    @Override
    public final boolean isOpen() {
        final StreamMessage<T> upstream = this.upstream;
        if (upstream != null) {
            return upstream.isOpen();
        }

        return !whenComplete().isDone();
    }

    @Override
    public final boolean isEmpty() {
        final StreamMessage<T> upstream = this.upstream;
        if (upstream != null) {
            return upstream.isEmpty();
        }

        return !isOpen();
    }

    @Override
    public final long demand() {
        return pendingDemand;
    }

    @Override
    final void request(long n) {
        final SubscriptionImpl downstreamSubscription = this.downstreamSubscription;
        assert downstreamSubscription != null;

        if (downstreamSubscription.needsDirectInvocation()) {
            doRequest(n);
        } else {
            downstreamSubscription.executor().execute(() -> doRequest(n));
        }
    }

    private void doRequest(long n) {
        final Subscription upstreamSubscription = this.upstreamSubscription;
        if (upstreamSubscription != null) {
            upstreamSubscription.request(n);
        } else {
            pendingDemand += n;
        }
    }

    @Override
    final void cancel() {
        final SubscriptionImpl downstreamSubscription = this.downstreamSubscription;
        assert downstreamSubscription != null;

        if (downstreamSubscription.needsDirectInvocation()) {
            doCancel();
        } else {
            downstreamSubscription.executor().execute(this::doCancel);
        }
    }

    private void doCancel() {
        final Subscription upstreamSubscription = this.upstreamSubscription;
        if (upstreamSubscription != null) {
            try {
                upstreamSubscription.cancel();
            } finally {
                // Clear the subscriber when we become sure that the upstream will not produce events anymore.
                final StreamMessage<T> upstream = this.upstream;
                assert upstream != null;
                if (upstream.isComplete()) {
                    downstreamSubscription.clearSubscriber();
                } else {
                    upstream.whenComplete().handle((u1, u2) -> {
                        downstreamSubscription.clearSubscriber();
                        return null;
                    });
                }
            }
        } else {
            abort(CancelledSubscriptionException.get());
        }
    }

    @Override
    final SubscriptionImpl subscribe(SubscriptionImpl subscription) {
        if (!downstreamSubscriptionUpdater.compareAndSet(this, null, subscription)) {
            final SubscriptionImpl oldSubscription = downstreamSubscription;
            assert oldSubscription != null;
            return oldSubscription;
        }

        final Subscriber<Object> subscriber = subscription.subscriber();
        if (subscription.needsDirectInvocation()) {
            subscribe(subscription, subscriber);
        } else {
            subscription.executor().execute(() -> {
                subscribe(subscription, subscriber);
            });
        }

        return subscription;
    }

    private void subscribe(SubscriptionImpl subscription, Subscriber<Object> subscriber) {
        if (downstreamOnSubscribeCalled) {
            // abort is called.
            return;
        }
        downstreamOnSubscribeCalled = true;
        try {
            subscriber.onSubscribe(subscription);
        } catch (Throwable t) {
            abort(t);
            throwIfFatal(t);
            logger.warn("Subscriber.onSubscribe() should not raise an exception. subscriber: {}",
                        subscriber, t);
            return;
        }
        safeOnSubscribeToUpstream();
    }

    private void safeOnSubscribeToUpstream() {
        final StreamMessage<T> upstream = this.upstream;
        final SubscriptionImpl downstreamSubscription = this.downstreamSubscription;
        if (upstream == null || downstreamSubscription == null || downstreamSubscription == NOOP_SUBSCRIPTION) {
            return;
        }

        if (!subscribedToUpstreamUpdater.compareAndSet(this, 0, 1)) {
            return;
        }

        upstream.subscribe(new ForwardingSubscriber(downstreamSubscription.subscriber()),
                           downstreamSubscription.executor(), downstreamSubscription.options());
    }

    @Override
    public final void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public final void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        if (!abortCauseUpdater.compareAndSet(this, null, cause)) {
            return;
        }

        if (!subscribedToUpstreamUpdater.compareAndSet(this, 0, 1)) {
            // Already subscribed to upstream so just abort upstream.
            final StreamMessage<T> upstream = this.upstream;
            assert upstream != null;
            upstream.abort(cause);
            return;
        }

        // The upstream wouldn't be subscribed to by the downstream.
        // So we need to abort upstream and complete the downstream with the cause.
        // Upstream.abort() is called here and in delegate(StreamMessage<T> upstream) method.
        // It's safe to call upstream.abort() multiple times.

        // The downstream.onError() is called in downstreamOnError method if it's already set.
        // If it wasn't set yet, downstream.onError() will be called in
        // CancellableStreamMessage.failLateSubscriber().

        // Abort upstream if it's set.
        final StreamMessage<T> upstream = this.upstream;
        if (upstream != null) {
            upstream.abort(cause);
        }

        final SubscriptionImpl newSubscription = new SubscriptionImpl(
                this, AbortingSubscriber.get(cause), ImmediateEventExecutor.INSTANCE, EMPTY_OPTIONS);
        if (downstreamSubscriptionUpdater.compareAndSet(this, null, newSubscription)) {
            // Downstream wasn't set yet.
            whenComplete().completeExceptionally(cause);
            return;
        }

        // Downstream was already set but upstream wasn't set yet or wouldn't be subscribed by the downstream.

        //noinspection unchecked
        final CompletableFuture<List<?>> collectingFuture =
                (CompletableFuture<List<?>>) (CompletableFuture<?>) this.collectingFuture;
        if (collectingFuture != null && collectingFuture != NO_COLLECTING_FUTURE) {
            collectingFuture.completeExceptionally(cause);
        }

        final SubscriptionImpl downstreamSubscription = this.downstreamSubscription;
        assert downstreamSubscription != null;
        if (downstreamSubscription.needsDirectInvocation()) {
            downstreamOnError(cause, downstreamSubscription);
        } else {
            downstreamSubscription.executor().execute(
                    () -> downstreamOnError(cause, downstreamSubscription));
        }
    }

    private void downstreamOnError(Throwable cause, SubscriptionImpl downstreamSubscription) {
        final Subscriber<Object> subscriber = downstreamSubscription.subscriber();
        try {
            if (!downstreamOnSubscribeCalled) {
                downstreamOnSubscribeCalled = true;
                subscriber.onSubscribe(downstreamSubscription);
            }

            if (downstreamSubscription.shouldNotifyCancellation() ||
                !(cause instanceof CancelledSubscriptionException)) {
                subscriber.onError(cause);
            }
        } catch (Throwable t) {
            final Exception composite = new CompositeException(t, cause);
            throwIfFatal(t);
            logger.warn("Subscriber.onSubscribe() or onError() should not raise an exception. subscriber: {}",
                        subscriber, composite);
        }
        whenComplete().completeExceptionally(cause);
    }

    @Override
    public CompletableFuture<List<T>> collect(EventExecutor executor, SubscriptionOption... options) {
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        if (!downstreamSubscriptionUpdater.compareAndSet(this, null, NOOP_SUBSCRIPTION)) {
            final Subscriber<Object> subscriber = downstreamSubscription.subscriber();
            final Throwable cause = abortedOrLate(subscriber);
            final CompletableFuture<List<T>> collectingFuture = new CompletableFuture<>();
            collectingFuture.completeExceptionally(cause);
            return collectingFuture;
        }

        // An atomic operation on multiple subscribers will be handled by upstream.collect()
        final StreamMessage<T> upstream = this.upstream;
        if (upstream != null) {
            return upstream.collect(executor, options);
        }

        final CompletableFuture<List<T>> collectingFuture = new CompletableFuture<>();
        collectingExecutor = executor;
        collectionOptions = options;
        if (collectingFutureUpdater.compareAndSet(this, null, collectingFuture)) {
            final Throwable abortCause = this.abortCause;
            if (abortCause != null) {
                collectingFuture.completeExceptionally(abortCause);
            }
            return collectingFuture;
        } else {
            final StreamMessage<T> upstream0 = this.upstream;
            assert upstream0 != null;
            return upstream0.collect(executor, options);
        }
    }

    private static SubscriptionImpl noopSubscription() {
        return new SubscriptionImpl(NoopCancellableStreamMessage.INSTANCE, NoopSubscriber.get(),
                                    ImmediateEventExecutor.INSTANCE, EMPTY_OPTIONS);
    }

    private final class ForwardingSubscriber implements Subscriber<T> {

        private final Subscriber<Object> downstreamSubscriber;

        ForwardingSubscriber(Subscriber<Object> downstreamSubscriber) {
            this.downstreamSubscriber = downstreamSubscriber;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            upstreamSubscription = subscription;
            if (pendingDemand > 0) {
                upstreamSubscription.request(pendingDemand);
                pendingDemand = 0;
            }
        }

        @Override
        public void onNext(T t) {
            downstreamSubscriber.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            downstreamSubscriber.onError(t);
        }

        @Override
        public void onComplete() {
            downstreamSubscriber.onComplete();
        }
    }
}

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

package com.linecorp.armeria.internal.common.stream;

import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsNotifyCancellation;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.util.concurrent.EventExecutor;

public final class RecoverableStreamMessage<T> implements StreamMessage<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<RecoverableStreamMessage> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(RecoverableStreamMessage.class, "subscribed");

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    private final StreamMessage<T> upstream;
    private final Function<Throwable, StreamMessage<T>> errorFunction;
    private final boolean allowResuming;

    @Nullable
    private volatile StreamMessage<T> fallbackStream;
    @Nullable
    private volatile EventExecutor executor;
    private volatile int subscribed;

    public RecoverableStreamMessage(StreamMessage<T> upstream,
                                    Function<? super Throwable, ? extends StreamMessage<T>> errorFunction,
                                    boolean allowResuming) {
        this.upstream = upstream;
        //noinspection unchecked
        this.errorFunction = (Function<Throwable, StreamMessage<T>>) errorFunction;
        this.allowResuming = allowResuming;
    }

    @Override
    public boolean isOpen() {
        final StreamMessage<T> fallbackStream = this.fallbackStream;
        if (fallbackStream != null) {
            return fallbackStream.isOpen();
        }
        return upstream.isOpen();
    }

    @Override
    public boolean isEmpty() {
        if (upstream.isEmpty()) {
            final StreamMessage<T> fallbackStream = this.fallbackStream;
            if (fallbackStream == null) {
                return true;
            }
            return fallbackStream.isEmpty();
        }
        return false;
    }

    @Override
    public long demand() {
        final StreamMessage<T> fallbackStream = this.fallbackStream;
        if (fallbackStream != null) {
            return fallbackStream.demand();
        }
        return upstream.demand();
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
            if (executor.inEventLoop()) {
                abortLateSubscriber(subscriber);
            } else {
                executor.execute(() -> abortLateSubscriber(subscriber));
            }
            return;
        }

        this.executor = executor;
        upstream.subscribe(new RecoverableSubscriber(subscriber, executor, options), executor, options);
    }

    private void abortLateSubscriber(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(NoopSubscription.get());
        subscriber.onError(new IllegalStateException("subscribed by other subscriber already"));
    }

    @Override
    public void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        final EventExecutor executor = this.executor;
        if (executor == null || executor.inEventLoop()) {
            abort0(cause);
        } else {
            executor.execute(() -> abort0(cause));
        }
    }

    @Override
    public CompletableFuture<List<T>> collect(EventExecutor executor, SubscriptionOption... options) {
        if (allowResuming) {
            // `upstream.collect()` either completes all elements into a list or exceptionally completes an
            // exception. So the downstream can't see the elements written before an error with the `upstream
            // .collect()`.
            // However, `StreamMessage.collect()`, which uses Subscriber to collect the upstream's elements
            // one by one, can deliver successfully published objects to the downstream before an error and
            // resume the error with a fallback stream.
            return StreamMessage.super.collect(executor, options);
        }

        if (!subscribedUpdater.compareAndSet(this, 0, 1)) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(
                    new IllegalStateException("subscribed by other subscriber already"));
        }

        return upstream.collect(executor, options).handle((objects, cause) -> {
            if (cause != null) {
                cause = Exceptions.peel(cause);
                // Switch the upstream to a fallback stream and resume subscribing.
                final StreamMessage<T> fallback = errorFunction.apply(cause);
                requireNonNull(fallback, "errorFunction.apply() returned null");
                return fallback.collect(executor, options).handle((res, fallbackCause) -> {
                    if (fallbackCause != null) {
                        fallbackCause = Exceptions.peel(fallbackCause);
                        completionFuture.completeExceptionally(fallbackCause);
                        return Exceptions.throwUnsafely(fallbackCause);
                    }
                    completionFuture.complete(null);
                    return res;
                });
            }
            completionFuture.complete(null);
            return UnmodifiableFuture.completedFuture(objects);
        }).thenCompose(Function.identity());
    }

    private void abort0(Throwable cause) {
        final StreamMessage<T> fallbackStream = this.fallbackStream;
        if (fallbackStream != null) {
            fallbackStream.abort(cause);
        } else {
            upstream.abort(cause);
        }
    }

    private final class RecoverableSubscriber extends SubscriptionArbiter implements Subscriber<T> {

        private Subscriber<T> downstream;
        private final EventExecutor executor;
        private final SubscriptionOption[] options;

        private boolean errorHandled;
        private boolean wroteAny;
        private boolean complete;

        private RecoverableSubscriber(Subscriber<? super T> downstream, EventExecutor executor,
                                      SubscriptionOption[] options) {
            super(executor);
            //noinspection unchecked
            this.downstream = (Subscriber<T>) downstream;
            this.executor = executor;
            this.options = options;
        }

        @Override
        public void onSubscribe(Subscription s) {
            setUpstreamSubscription(s);
            if (!errorHandled) {
                downstream.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T obj) {
            if (!isTransient(obj)) {
                wroteAny = true;
            }
            produced(1);
            downstream.onNext(obj);
        }

        private boolean isTransient(T obj) {
            // TODO(ikhoon): Generalize this predicate to handle more types.
            return obj instanceof ResponseHeaders && ((ResponseHeaders) obj).status().isInformational();
        }

        @Override
        public void onError(Throwable cause) {
            if (complete) {
                return;
            }

            if (cause instanceof AbortedStreamException || cause instanceof CancelledSubscriptionException) {
                // The upstream is aborted or cancelled by the subscriber. No need to resume the stream.
                onError0(cause);
                return;
            }

            if (errorHandled) {
                // The fallback stream also raised an error.
                onError0(cause);
                return;
            }

            final boolean canApplyFallback = allowResuming || !wroteAny;
            if (!canApplyFallback) {
                onError0(cause);
                return;
            }

            errorHandled = true;
            try {
                // Switch the upstream to a fallback stream and resume subscribing.
                final StreamMessage<T> fallback = errorFunction.apply(cause);
                requireNonNull(fallback, "errorFunction.apply() returned null");
                fallbackStream = fallback;
                fallback.subscribe(this, executor, options);
            } catch (Throwable t) {
                onError0(new CompositeException(t, cause));
            }
        }

        private void onError0(Throwable cause) {
            complete = true;
            downstream.onError(cause);
            completionFuture.completeExceptionally(cause);
        }

        @Override
        public void onComplete() {
            if (complete) {
                return;
            }
            complete = true;
            downstream.onComplete();
            completionFuture.complete(null);
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
            if (complete) {
                return;
            }
            complete = true;

            doCancel();
            final CancelledSubscriptionException cause = CancelledSubscriptionException.get();
            if (containsNotifyCancellation(options)) {
                downstream.onError(cause);
            }
            downstream = NoopSubscriber.get();
            completionFuture.completeExceptionally(cause);
        }
    }
}

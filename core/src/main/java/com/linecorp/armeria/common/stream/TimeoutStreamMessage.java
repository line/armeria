/*
 * Copyright 2025 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.StreamTimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * This class provides timeout functionality to a base {@link StreamMessage}.
 * The provided {@link StreamTimeoutStrategy} is used to evaluate timeouts,
 * and if a timeout is detected, a {@link StreamTimeoutException} is thrown.
 *
 * <p>The timeout functionality helps to release resources and throw appropriate exceptions
 * if the stream becomes inactive or data is delayed, thereby improving system efficiency.</p>
 *
 * @param <T> the type of the elements signaled
 */
final class TimeoutStreamMessage<T> implements StreamMessage<T> {

    private final StreamMessage<? extends T> delegate;

    private final StreamTimeoutStrategy timeoutStrategy;

    /**
     * Creates a new {@link TimeoutStreamMessage} with the specified base stream message
     * and a strategy for evaluating timeouts.
     *
     * @param delegate the original stream message
     * @param timeoutStrategy the strategy used to determine timeout behavior
     */
    TimeoutStreamMessage(StreamMessage<? extends T> delegate, StreamTimeoutStrategy timeoutStrategy) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.timeoutStrategy = requireNonNull(timeoutStrategy, "timeoutStrategy");
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public long demand() {
        return delegate.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return delegate.whenComplete();
    }

    /**
     * Subscribes the given subscriber to this stream with timeout behavior applied
     * using the configured {@link StreamTimeoutStrategy}.
     *
     * @param subscriber the subscriber to this stream
     * @param executor the executor for running timeout tasks and stream operations
     * @param options subscription options
     * @see StreamMessage#subscribe(Subscriber, EventExecutor, SubscriptionOption...)
     */
    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        delegate.subscribe(new TimeoutSubscriber<>(subscriber, executor, timeoutStrategy),
                           executor, options);
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(cause);
    }

    static final class TimeoutSubscriber<T> implements Runnable, Subscriber<T>, Subscription {
        private final Subscriber<? super T> delegate;
        private final EventExecutor executor;
        private final StreamTimeoutStrategy timeoutStrategy;

        @Nullable
        private ScheduledFuture<?> timeoutFuture;
        @Nullable
        private Subscription subscription;
        private long lastEventTimeNanos;
        private boolean completed;
        private volatile boolean canceled;

        TimeoutSubscriber(Subscriber<? super T> delegate, EventExecutor executor,
                          StreamTimeoutStrategy timeoutStrategy) {
            this.delegate = requireNonNull(delegate, "delegate");
            this.executor = requireNonNull(executor, "executor");
            this.timeoutStrategy = requireNonNull(timeoutStrategy, "timeoutStrategy");
        }

        private void handleTimeoutDecision(StreamTimeoutDecision decision) {
            if (decision.timedOut()) {
                completed = true;
                delegate.onError(timeoutStrategy.newTimeoutException());
                assert subscription != null;
                subscription.cancel();
                return;
            }

            if (decision.nextDelayNanos() == 0) {
                timeoutFuture = null;
                return;
            }

            timeoutFuture = scheduleTimeout(decision.nextDelayNanos());
        }

        private ScheduledFuture<?> scheduleTimeout(long delay) {
            return executor.schedule(this, delay, TimeUnit.NANOSECONDS);
        }

        void cancelSchedule() {
            if (timeoutFuture != null && !timeoutFuture.isCancelled()) {
                timeoutFuture.cancel(false);
            }
        }

        @Override
        public void run() {
            final long currentTimeNanos = System.nanoTime();
            final StreamTimeoutDecision decision =
                    timeoutStrategy.evaluateTimeout(currentTimeNanos, lastEventTimeNanos);
            handleTimeoutDecision(decision);
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            delegate.onSubscribe(this);
            if (completed || canceled) {
                return;
            }
            lastEventTimeNanos = System.nanoTime();
            final StreamTimeoutDecision decision = timeoutStrategy.initialDecision();
            handleTimeoutDecision(decision);
        }

        @Override
        public void onNext(T t) {
            if (completed || canceled) {
                PooledObjects.close(t);
                return;
            }
            lastEventTimeNanos = System.nanoTime();
            delegate.onNext(t);
        }

        @Override
        public void onError(Throwable throwable) {
            if (completed) {
                return;
            }
            completed = true;
            cancelSchedule();
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (completed) {
                return;
            }
            completed = true;
            cancelSchedule();
            delegate.onComplete();
        }

        @Override
        public void request(long l) {
            assert subscription != null;
            subscription.request(l);
        }

        @Override
        public void cancel() {
            canceled = true;
            cancelSchedule();
            assert subscription != null;
            subscription.cancel();
        }
    }
}

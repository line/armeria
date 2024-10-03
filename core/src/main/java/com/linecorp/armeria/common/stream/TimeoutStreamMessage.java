/*
 * Copyright 2024 LINE Corporation
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

import java.time.Duration;
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
 * This class provides timeout functionality to a base StreamMessage.
 * If data is not received within the specified time, a {@link StreamTimeoutException} is thrown.
 *
 * <p>The timeout functionality helps to release resources and throw appropriate exceptions
 * if the stream becomes inactive or data is not received within a certain time frame,
 * thereby improving system efficiency.
 *
 * @param <T> the type of the elements signaled
 */
final class TimeoutStreamMessage<T> implements StreamMessage<T> {

    private final StreamMessage<? extends T> delegate;
    private final Duration timeoutDuration;
    private final StreamTimeoutMode timeoutMode;

    /**
     * Creates a new TimeoutStreamMessage with the specified base stream message and timeout settings.
     *
     * @param delegate the original stream message
     * @param timeoutDuration the duration before a timeout occurs
     * @param timeoutMode the mode in which the timeout is applied (see {@link StreamTimeoutMode} for details)
     */
    TimeoutStreamMessage(StreamMessage<? extends T> delegate, Duration timeoutDuration,
                         StreamTimeoutMode timeoutMode) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.timeoutDuration = requireNonNull(timeoutDuration, "timeoutDuration");
        this.timeoutMode = requireNonNull(timeoutMode, "timeoutMode");
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
     * Subscribes the given subscriber to this stream with timeout logic applied.
     *
     * @param subscriber the subscriber to this stream
     * @param executor the executor for running timeout tasks and stream operations
     * @param options subscription options
     * @see StreamMessage#subscribe(Subscriber, EventExecutor, SubscriptionOption...)
     */
    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        delegate.subscribe(new TimeoutSubscriber<>(subscriber, executor, timeoutDuration, timeoutMode),
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

        private static final String TIMEOUT_MESSAGE = "Stream timed out after %d ms (timeout mode: %s)";
        private final Subscriber<? super T> delegate;
        private final EventExecutor executor;
        private final StreamTimeoutMode timeoutMode;
        private final Duration timeoutDuration;
        private final long timeoutNanos;
        @Nullable
        private ScheduledFuture<?> timeoutFuture;
        @Nullable
        private Subscription subscription;
        private long lastEventTimeNanos;
        private boolean completed;
        private volatile boolean canceled;

        TimeoutSubscriber(Subscriber<? super T> delegate, EventExecutor executor, Duration timeoutDuration,
                          StreamTimeoutMode timeoutMode) {
            this.delegate = requireNonNull(delegate, "delegate");
            this.executor = requireNonNull(executor, "executor");
            this.timeoutDuration = requireNonNull(timeoutDuration, "timeoutDuration");
            timeoutNanos = timeoutDuration.toNanos();
            this.timeoutMode = requireNonNull(timeoutMode, "timeoutMode");
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
            if (timeoutMode == StreamTimeoutMode.UNTIL_NEXT) {
                final long currentTimeNanos = System.nanoTime();
                final long elapsedNanos = currentTimeNanos - lastEventTimeNanos;

                if (elapsedNanos < timeoutNanos) {
                    final long delayNanos = timeoutNanos - elapsedNanos;
                    timeoutFuture = scheduleTimeout(delayNanos);
                    return;
                }
            }
            completed = true;
            delegate.onError(new StreamTimeoutException(
                    String.format(TIMEOUT_MESSAGE, timeoutDuration.toMillis(), timeoutMode)));
            assert subscription != null;
            subscription.cancel();
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            delegate.onSubscribe(this);
            if (completed || canceled) {
                return;
            }
            lastEventTimeNanos = System.nanoTime();
            timeoutFuture = scheduleTimeout(timeoutNanos);
        }

        @Override
        public void onNext(T t) {
            if (completed || canceled) {
                PooledObjects.close(t);
                return;
            }
            switch (timeoutMode) {
                case UNTIL_NEXT:
                    lastEventTimeNanos = System.nanoTime();
                    break;
                case UNTIL_FIRST:
                    cancelSchedule();
                    timeoutFuture = null;
                    break;
                case UNTIL_EOS:
                    break;
            }
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

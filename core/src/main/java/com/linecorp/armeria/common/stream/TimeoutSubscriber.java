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
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

final class TimeoutSubscriber<T> implements Runnable, Subscriber<T>, Subscription {

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

    TimeoutSubscriber(Subscriber<? super T> delegate, EventExecutor executor, Duration timeoutDuration,
                      StreamTimeoutMode timeoutMode) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.executor = requireNonNull(executor, "executor");
        this.timeoutDuration = requireNonNull(timeoutDuration, "timeoutDuration");
        timeoutNanos = timeoutDuration.toNanos();
        this.timeoutMode = requireNonNull(timeoutMode, "timeoutMode");
    }

    private ScheduledFuture<?> scheduleTimeout(long delay, TimeUnit unit) {
        return executor.schedule(this, delay, unit);
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
                timeoutFuture = scheduleTimeout(delayNanos, TimeUnit.NANOSECONDS);
                return;
            }
        }
        completed = true;
        delegate.onError(new TimeoutException(
                String.format(TIMEOUT_MESSAGE, timeoutDuration.toMillis(), timeoutMode)));
        subscription.cancel();
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription = s;
        lastEventTimeNanos = System.nanoTime();
        timeoutFuture = scheduleTimeout(timeoutNanos, TimeUnit.NANOSECONDS);
        delegate.onSubscribe(this);
    }

    @Override
    public void onNext(T t) {
        if (completed) {
            return;
        }
        switch (timeoutMode) {
            case UNTIL_NEXT:
                lastEventTimeNanos = System.nanoTime();
                break;
            case UNTIL_FIRST:
                cancelSchedule();
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
        subscription.request(l);
    }

    @Override
    public void cancel() {
        cancelSchedule();
        subscription.cancel();
    }
}

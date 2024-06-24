/*
 * Copyright 2019 LINE Corporation
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
import java.util.concurrent.TimeoutException;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

public class TimeoutSubscriber<T> implements Subscriber<T> {
    private static final String TIMEOUT_MESSAGE = "Stream timed out after %d ms (timeout mode: %s)";
    private final Subscriber<? super T> delegate;
    private final EventExecutor executor;
    private final StreamTimeoutMode timeoutMode;
    private final Duration timeoutDuration;
    private ScheduledFuture<?> timeoutFuture;
    private Runnable timeoutTask;
    private Subscription subscription;
    private long timeoutNanos;
    private long lastOnNextTimeNanos;

    public TimeoutSubscriber(Subscriber<? super T> delegate, EventExecutor executor, Duration timeoutDuration, StreamTimeoutMode timeoutMode) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.executor = requireNonNull(executor, "executor");
        this.timeoutDuration = requireNonNull(timeoutDuration, "timeoutDuration");
        timeoutNanos = timeoutDuration.toNanos();
        this.timeoutMode = requireNonNull(timeoutMode, "timeoutMode");
        timeoutTask = createTimeoutTask();
    }

    private Runnable createTimeoutTask() {
        return () -> {
            if(timeoutMode == StreamTimeoutMode.UNTIL_NEXT) {
                long currentTimeNanos = System.nanoTime();
                long elapsedNanos = currentTimeNanos - lastOnNextTimeNanos;

                if(elapsedNanos <= timeoutNanos) {
                    long delayNanos = timeoutNanos - (currentTimeNanos - lastOnNextTimeNanos);
                    timeoutFuture = createTimeoutSchedule(delayNanos, TimeUnit.NANOSECONDS);
                    return;
                }
            }
            subscription.cancel();
            delegate.onError(new TimeoutException(String.format(TIMEOUT_MESSAGE, timeoutDuration.toMillis(), timeoutMode)));
        };
    }

    private ScheduledFuture<?> createTimeoutSchedule(long delay, TimeUnit unit) {
        return executor.schedule(timeoutTask, delay, unit);
    }

    private void cancelSchedule() {
        if(!timeoutFuture.isCancelled()) {
            timeoutFuture.cancel(false);
        }
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription = s;
        lastOnNextTimeNanos = System.nanoTime();
        timeoutFuture = createTimeoutSchedule(timeoutNanos, TimeUnit.NANOSECONDS);
        delegate.onSubscribe(s);
    }

    @Override
    public void onNext(T t) {
        if (timeoutFuture.isCancelled()) {
            return;
        }
        switch (timeoutMode) {
            case UNTIL_NEXT:
                lastOnNextTimeNanos = System.nanoTime();
                break;
            case UNTIL_FIRST:
                timeoutFuture.cancel(false);
                break;
            case UNTIL_EOS:
                break;
        }
        delegate.onNext(t);
    }

    @Override
    public void onError(Throwable throwable) {
        cancelSchedule();
        delegate.onError(throwable);
    }

    @Override
    public void onComplete() {
        cancelSchedule();
        delegate.onComplete();
    }
}

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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

public class TimeoutSubscriber<T> implements Subscriber<T> {

    private final Subscriber<? super T> delegate;
    private final EventExecutor executor;

    private final StreamTimeoutMode streamTimeoutMode;
    private ScheduledFuture<?> timeoutFuture;

    private Subscription subscription;
    private final long timeoutMillis;

    public TimeoutSubscriber(Subscriber<? super T> delegate, EventExecutor executor, long timeoutMillis, StreamTimeoutMode streamTimeoutMode) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.executor = requireNonNull(executor, "executor");
        this.timeoutMillis = timeoutMillis;
        this.streamTimeoutMode = requireNonNull(streamTimeoutMode, "streamTimeoutMode");
    }

    private ScheduledFuture<?> scheduleTimeout() {
        return executor.schedule(() -> {
            subscription.cancel();
            delegate.onError(new TimeoutException(
                    String.format("Stream timed out after %d ms (timeout mode: %s)", timeoutMillis, streamTimeoutMode)));
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onSubscribe(Subscription s) {
        delegate.onSubscribe(s);
        subscription = s;
        timeoutFuture = scheduleTimeout();
    }

    @Override
    public void onNext(T t) {
        delegate.onNext(t);
        if (timeoutFuture == null) {
            return;
        }
        switch (streamTimeoutMode) {
            case UNTIL_NEXT:
                timeoutFuture.cancel(false);
                timeoutFuture = scheduleTimeout();
                break;
            case UNTIL_FIRST:
                timeoutFuture.cancel(false);
                timeoutFuture = null;
                break;
            case UNTIL_EOS:
                break;
        }
    }

    @Override
    public void onError(Throwable throwable) {
        if(timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        delegate.onError(throwable);
    }

    @Override
    public void onComplete() {
        if(timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        delegate.onComplete();
    }
}

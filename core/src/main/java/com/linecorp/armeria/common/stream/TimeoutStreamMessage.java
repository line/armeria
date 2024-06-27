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
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import io.netty.util.concurrent.EventExecutor;

public class TimeoutStreamMessage<T> implements StreamMessage<T> {

    private final StreamMessage<? extends T> delegate;
    private final Duration timeoutDuration;
    private final StreamTimeoutMode timeoutMode;
    private TimeoutSubscriber<T> timeoutSubscriber;

    public TimeoutStreamMessage(StreamMessage<? extends T> delegate, Duration timeoutDuration,
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

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        timeoutSubscriber = new TimeoutSubscriber<T>(subscriber, executor, timeoutDuration, timeoutMode);
        delegate.subscribe(timeoutSubscriber, executor, options);
    }

    private void cancelSchedule() {
        if (timeoutSubscriber != null) {
            timeoutSubscriber.cancelSchedule();
        }
    }

    @Override
    public void abort() {
        cancelSchedule();
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        cancelSchedule();
        delegate.abort(cause);
    }
}
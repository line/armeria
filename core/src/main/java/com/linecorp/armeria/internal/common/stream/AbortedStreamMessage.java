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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;

import io.netty.util.concurrent.EventExecutor;

public class AbortedStreamMessage<T> implements StreamMessage<T>, Subscription {

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<AbortedStreamMessage> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(AbortedStreamMessage.class, "subscribed");

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();
    private final Throwable cause;
    private volatile int subscribed;

    public AbortedStreamMessage(Throwable cause) {
        this.cause = cause;
    }

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public long demand() {
        return 0;
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
            subscriber.onError(new IllegalStateException("subscribed by other subscriber already"));
            return;
        }

        if (executor.inEventLoop()) {
            subscribe0(subscriber);
        } else {
            executor.execute(() -> subscribe0(subscriber));
        }
    }

    private void subscribe0(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(this);
        subscriber.onError(cause);
        completionFuture.completeExceptionally(cause);
    }

    @Override
    public void abort() {}

    @Override
    public void abort(Throwable cause) {}

    @Override
    public void request(long n) {}

    @Override
    public void cancel() {}
}

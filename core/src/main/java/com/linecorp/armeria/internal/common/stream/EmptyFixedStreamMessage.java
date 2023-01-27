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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link FixedStreamMessage} that publishes no objects, just a close event.
 */
@UnstableApi
public class EmptyFixedStreamMessage<T> extends FixedStreamMessage<T> {

    @Override
    public boolean isComplete() {
        return whenComplete().isDone();
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        if (executor.inEventLoop()) {
            subscribe0(subscriber);
        } else {
            executor.execute(() -> subscribe0(subscriber));
        }
    }

    private void subscribe0(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(NoopSubscription.get());
        subscriber.onComplete();
        whenComplete().complete(null);
    }

    @Override
    public CompletableFuture<List<T>> collect(EventExecutor executor, SubscriptionOption... options) {
        whenComplete().complete(null);
        return UnmodifiableFuture.completedFuture(ImmutableList.of());
    }

    @Override
    public final boolean isEmpty() {
        return true;
    }

    @Override
    public long demand() {
        return 0;
    }

    @Override
    final void cleanupObjects(@Nullable Throwable cause) {
        // Empty streams have no objects to clean.
    }

    @Override
    protected final List<T> drainAll(boolean withPooledObjects) {
        return ImmutableList.of();
    }

    @Override
    public void request(long n) {}

    @Override
    public void cancel() {
        whenComplete().complete(null);
    }

    @Override
    public void abort() {
        whenComplete().complete(null);
    }

    @Override
    public void abort(Throwable cause) {
        whenComplete().complete(null);
    }
}

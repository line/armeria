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

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.stream.StreamMessageUtil;

import io.netty.util.concurrent.EventExecutor;

final class AsyncMapStreamMessage<T, U> implements StreamMessage<U> {
    private final StreamMessage<Object> source;
    private final Function<Object, CompletableFuture<U>> function;

    @SuppressWarnings("unchecked")
    AsyncMapStreamMessage(StreamMessage<? extends T> source,
                                 Function<? super T, ? extends CompletableFuture<U>> function) {
        requireNonNull(source, "source");
        requireNonNull(function, "function");

        this.source = (StreamMessage<Object>) source;
        this.function = (Function<Object, CompletableFuture<U>>) function;
    }

    @Override
    public boolean isOpen() {
        return source.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return source.isEmpty();
    }

    @Override
    public long demand() {
        return source.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return source.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super U> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        source.subscribe(new AsyncMapSubscriber<>(subscriber, function), executor, options);
    }

    @Override
    public void abort() {
        source.abort();
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        source.abort(cause);
    }

    private static final class AsyncMapSubscriber<U> implements Subscriber<Object>, Subscription {
        private final Subscriber<? super U> downstream;
        private final Function<Object, CompletableFuture<U>> function;

        @Nullable
        private volatile Subscription upstream;
        private volatile boolean canceled;

        AsyncMapSubscriber(Subscriber<? super U> downstream,
                           Function<Object, CompletableFuture<U>> function) {
            requireNonNull(downstream, "downstream");
            requireNonNull(function, "function");
            this.downstream = downstream;
            this.function = function;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            upstream = subscription;
            downstream.onSubscribe(this);
        }

        @Override
        public void onNext(Object item) {
            requireNonNull(item, "item");

            if (canceled) {
                StreamMessageUtil.closeOrAbort(item);
                return;
            }

            try {
                final CompletableFuture<U> future = function.apply(item);
                requireNonNull(future, "function.apply() returned null");
                future.whenComplete((res, cause) -> {
                    try {
                        if (cause != null) {
                            upstream.cancel();
                            downstream.onError(cause);
                        } else {
                            requireNonNull(res, "function.apply()'s future completed with null");
                            downstream.onNext(res);
                        }
                    } catch (Throwable ex) {
                        StreamMessageUtil.closeOrAbort(item, ex);
                        upstream.cancel();
                        onError(ex);
                    }
                });
            } catch (Throwable ex) {
                StreamMessageUtil.closeOrAbort(item, ex);
                upstream.cancel();
                onError(ex);
            }
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");
            if (canceled) {
                return;
            }
            canceled = true;

            downstream.onError(cause);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }

        @Override
        public void request(long n) {
            if (canceled) {
                return;
            }

            upstream.request(n);
        }

        @Override
        public void cancel() {
            if (canceled) {
                return;
            }

            canceled = true;
            upstream.cancel();
        }
    }
}

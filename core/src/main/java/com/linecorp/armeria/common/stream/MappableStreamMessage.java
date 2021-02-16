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

import static com.linecorp.armeria.common.stream.StreamMessageUtil.EMPTY_OPTIONS;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.stream.MapperFunction.Type;

import io.netty.util.concurrent.EventExecutor;

final class MappableStreamMessage<T, U> implements StreamMessage<U> {

    static <T> MappableStreamMessage<T, T> of(StreamMessage<? extends T> source,
                                              Predicate<? super T> predicate) {
        return new MappableStreamMessage<>(source, MapperFunction.of(predicate));
    }

    static <T, R> MappableStreamMessage<T, R> of(StreamMessage<? extends T> source,
                                                 Function<? super T, ? extends R> function) {
        return new MappableStreamMessage<>(source, MapperFunction.of(function));
    }

    private final StreamMessage<T> source;
    private final List<MapperFunction<Object, Object>> functions;

    @SuppressWarnings("unchecked")
    MappableStreamMessage(StreamMessage<? extends T> source, MapperFunction<? super T, ? extends U> function) {
        requireNonNull(source, "source");
        requireNonNull(function, "function");

        if (source instanceof MappableStreamMessage) {
            @SuppressWarnings("unchecked")
            final MappableStreamMessage<T, ?> cast = (MappableStreamMessage<T, ?>) source;
            this.source = cast.source;

            // Extract source functions and fuse them with function
            final List<MapperFunction<Object, Object>> functions = cast.functions;
            this.functions =
                    ImmutableList.<MapperFunction<Object, Object>>builderWithExpectedSize(functions.size() + 1)
                            .addAll(functions)
                            .add((MapperFunction<Object, Object>) function)
                            .build();
        } else {
            this.source = (StreamMessage<T>) source;
            functions = ImmutableList.of((MapperFunction<Object, Object>) function);
        }
    }

    @VisibleForTesting
    StreamMessage<T> upstream() {
        return source;
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
    public void subscribe(Subscriber<? super U> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, EMPTY_OPTIONS);
    }

    @Override
    public void subscribe(Subscriber<? super U> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        // TODO(ikhoon): Handle multiple subscrition
        source.subscribe(new MappableSubscriber<>(subscriber, functions), executor, options);
    }

    @Override
    public void abort() {
        source.abort();
    }

    @Override
    public void abort(Throwable cause) {
        source.abort(cause);
    }

    private static final class MappableSubscriber<U> implements Subscriber<Object>, Subscription {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<MappableSubscriber, Subscription> upstreamUpdater =
                AtomicReferenceFieldUpdater
                        .newUpdater(MappableSubscriber.class, Subscription.class, "upstream");

        private final Subscriber<? super U> downstream;
        private final List<MapperFunction<Object, Object>> functions;

        @Nullable
        private volatile Subscription upstream;

        MappableSubscriber(Subscriber<? super U> downstream, List<MapperFunction<Object, Object>> functions) {
            requireNonNull(downstream, "downstream");
            this.downstream = downstream;
            this.functions = functions;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (!upstreamUpdater.compareAndSet(this, null, subscription)) {
                subscription.cancel();
                throw new IllegalStateException("Subscription already set!");
            }
            upstream = subscription;
            downstream.onSubscribe(this);
        }

        @Override
        public void onNext(Object item) {
            requireNonNull(item, "item");

            final Subscription upstream = this.upstream;
            if (upstream != null) {

                Object result = item;
                try {
                    for (MapperFunction<Object, Object> function : functions) {
                        assert result != null;
                        final Type type = function.type();
                        if (type == Type.MAP) {
                            result = function.apply(result);
                        } else if (type == Type.FILTER) {
                            result = function.apply(result);
                            if (result == null) {
                                // The given item was filtered out. Should request the next item.
                                break;
                            }
                        } else {
                            // Should never reach here.
                            throw new Error();
                        }
                    }
                } catch (Throwable ex) {
                    upstream.cancel();
                    onError(ex);
                    return;
                }

                if (result != null) {
                    @SuppressWarnings("unchecked")
                    final U cast = (U) result;
                    downstream.onNext(cast);
                } else {
                    upstream.request(1);
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (upstream != null) {
                upstream = null;
                downstream.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (upstream != null) {
                upstream = null;
                downstream.onComplete();
            }
        }

        @Override
        public void request(long n) {
            final Subscription s = upstream;
            if (s != null) {
                s.request(n);
            }
        }

        @Override
        public void cancel() {
            final Subscription s = upstream;
            upstream = null;
            if (s != null) {
                s.cancel();
            }
        }
    }
}

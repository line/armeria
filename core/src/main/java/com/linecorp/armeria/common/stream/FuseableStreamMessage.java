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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.stream.NonOverridableStreamMessageWrapper;

import io.netty.util.concurrent.EventExecutor;

final class FuseableStreamMessage<T, U> implements StreamMessage<U> {

    static <T> FuseableStreamMessage<T, T> of(StreamMessage<? extends T> source,
                                              Predicate<? super T> predicate) {
        return new FuseableStreamMessage<>(source, MapperFunction.of(predicate), null);
    }

    static <T, R> FuseableStreamMessage<T, R> of(StreamMessage<? extends T> source,
                                                 Function<? super T, ? extends R> function) {
        return new FuseableStreamMessage<>(source, MapperFunction.of(function), null);
    }

    static <T> FuseableStreamMessage<T, T> error(
            StreamMessage<? extends T> source, Function<? super Throwable, ? extends Throwable> errorFunction) {
        return new FuseableStreamMessage<>(source, null, errorFunction);
    }

    // The `source` might not produce `T` and the emitted objects will be transformed to `U` by the `function`.
    private final StreamMessage<Object> source;
    @Nullable
    private final MapperFunction<Object, U> function;
    @Nullable
    private final Function<Throwable, Throwable> errorFunction;

    @SuppressWarnings("unchecked")
    private FuseableStreamMessage(StreamMessage<? extends T> source,
                                  @Nullable MapperFunction<T, U> function,
                                  @Nullable Function<? super Throwable, ? extends Throwable> errorFunction) {
        requireNonNull(source, "source");
        assert function != null || errorFunction != null;

        source = peel(source);
        if (source instanceof FuseableStreamMessage) {
            // The second type parameter of FuseableStreamMessage is bound to StreamMessage.
            // (e.g., FuseableStreamMessage<T, U> is subtype of StreamMessage<U>.)
            // So we don't know the first type when downcasting StreamMessage to FuseableStreamMessage.
            final FuseableStreamMessage<?, T> cast = (FuseableStreamMessage<?, T>) source;
            this.source = cast.source;

            // Extract source function and fuse it with the function
            if (function != null) {
                if (cast.function != null) {
                    this.function = cast.function.and(function);
                } else {
                    this.function = (MapperFunction<Object, U>) function;
                }
                this.errorFunction = cast.errorFunction;
            } else {
                if (cast.errorFunction != null) {
                    this.errorFunction = cast.errorFunction.andThen(errorFunction);
                } else {
                    this.errorFunction = (Function<Throwable, Throwable>) errorFunction;
                }
                this.function = (MapperFunction<Object, U>) cast.function;
            }
        } else {
            this.source = (StreamMessage<Object>) source;
            this.function = (MapperFunction<Object, U>) function;
            this.errorFunction = (Function<Throwable, Throwable>) errorFunction;
        }
    }

    private StreamMessage<? extends T> peel(StreamMessage<? extends T> source) {
        if (!(source instanceof NonOverridableStreamMessageWrapper)) {
            return source;
        }

        do {
            //noinspection unchecked
            source = ((NonOverridableStreamMessageWrapper<? extends T, ?>) source).delegate();
        } while (source instanceof NonOverridableStreamMessageWrapper);

        return source;
    }

    @VisibleForTesting
    StreamMessage<Object> upstream() {
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
    public CompletableFuture<List<U>> collect(EventExecutor executor, SubscriptionOption... options) {
        return source.collect(executor, options).handle((objs, cause) -> {
            if (cause != null) {
                if (errorFunction != null) {
                    try {
                        cause = errorFunction.apply(cause);
                        requireNonNull(cause, "errorFunction.apply() returned null");
                    } catch (Throwable t) {
                        cause = new CompositeException(t, cause);
                    }
                }
                return Exceptions.throwUnsafely(cause);
            }

            final ImmutableList.Builder<U> builder = ImmutableList.builderWithExpectedSize(objs.size());
            @Nullable Throwable cause0 = null;
            for (Object obj : objs) {
                if (cause0 != null) {
                    // An error was raised. The remaing objects should be released.
                    StreamMessageUtil.closeOrAbort(obj, cause0);
                    continue;
                }

                try {
                    @Nullable
                    final U result = function.apply(obj);
                    if (result != null) {
                        builder.add(result);
                    } else {
                        StreamMessageUtil.closeOrAbort(obj);
                    }
                } catch (Throwable ex) {
                    if (errorFunction != null) {
                        try {
                            ex = errorFunction.apply(ex);
                            requireNonNull(ex, "errorFunction.apply() returned null");
                        } catch (Throwable t) {
                            ex = new CompositeException(t, ex);
                        }
                    }
                    StreamMessageUtil.closeOrAbort(obj, ex);
                    cause0 = ex;
                }
            }

            final List<U> elements = builder.build();
            if (cause0 != null) {
                // An error was raised. The transformed objects should be released.
                for (U element : elements) {
                    StreamMessageUtil.closeOrAbort(element, cause0);
                }
                return Exceptions.throwUnsafely(cause0);
            } else {
                return elements;
            }
        });
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

        source.subscribe(new FuseableSubscriber<>(subscriber, function, errorFunction), executor, options);
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

    private static final class FuseableSubscriber<U> implements Subscriber<Object>, Subscription {

        private final Subscriber<? super U> downstream;
        @Nullable
        private final MapperFunction<Object, U> function;
        @Nullable
        private final Function<Throwable, Throwable> errorFunction;

        @Nullable
        private volatile Subscription upstream;
        private volatile boolean canceled;

        FuseableSubscriber(Subscriber<? super U> downstream, @Nullable MapperFunction<Object, U> function,
                           @Nullable Function<Throwable, Throwable> errorFunction) {
            requireNonNull(downstream, "downstream");
            this.downstream = downstream;
            this.function = function;
            this.errorFunction = errorFunction;
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

            @Nullable U result = null;
            try {
                if (function != null) {
                    result = function.apply(item);
                } else {
                    //noinspection unchecked
                    result = (U) item;
                }
                if (result != null) {
                    downstream.onNext(result);
                } else {
                    StreamMessageUtil.closeOrAbort(item);
                    upstream.request(1);
                }
            } catch (Throwable ex) {
                StreamMessageUtil.closeOrAbort(item, ex);
                if (result != null && item != result) {
                    StreamMessageUtil.closeOrAbort(result, ex);
                }
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

            if (errorFunction != null) {
                Throwable transformed;
                try {
                    transformed = errorFunction.apply(cause);
                    requireNonNull(transformed, "errorFunction.apply() returned null");
                } catch (Throwable t) {
                    transformed = new CompositeException(t, cause);
                }
                downstream.onError(transformed);
            } else {
                downstream.onError(cause);
            }
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

    /**
     * Represents either a {@link Function} or a {@link Predicate}.
     */
    @FunctionalInterface
    interface MapperFunction<T, R> extends Function<T, R> {

        /**
         * Creates a new {@link MapperFunction} from the specified {@link Function}.
         */
        static <T, R> MapperFunction<T, R> of(Function<? super T, ? extends R> function) {
            requireNonNull(function, "function");
            return o -> {
                final R result = function.apply(o);
                requireNonNull(result, "function.apply() returned null");
                return result;
            };
        }

        /**
         * Creates a new {@link MapperFunction} from the specified {@link Predicate}.
         */
        static <T> MapperFunction<T, T> of(Predicate<? super T> predicate) {
            requireNonNull(predicate, "predicate");

            return o -> {
                final boolean result = predicate.test(o);
                if (result) {
                    return o;
                } else {
                    return null;
                }
            };
        }

        default <V> MapperFunction<T, V> and(MapperFunction<? super R, ? extends V> after) {
            return in -> {
                @Nullable
                final R out = apply(in);
                if (out != null) {
                    return after.apply(out);
                } else {
                    // Stop chaining
                    return null;
                }
            };
        }

        /**
         * Use {{@link #and(MapperFunction)}} instead.
         */
        @Override
        default <V> Function<T, V> andThen(Function<? super R, ? extends V> after) {
            throw new UnsupportedOperationException("Must use and(MapperFunction) instead.");
        }

        @Override
        default <V> Function<V, R> compose(Function<? super V, ? extends T> before) {
            throw new UnsupportedOperationException(
                    "compose is not allowed for " + MapperFunction.class.getName());
        }

        /**
         * Applies this function to the given argument.
         *
         * <p>If this {@link MapperFunction} is created from a {@link Predicate},
         * returns the given argument itself if the argument passes the filter, or {@code null} otherwise.
         * If this {@link MapperFunction} is created from a {@link Function}, returns a transformed value from
         * the given argument. {@code null} is not allowed to return.
         */
        @Nullable
        @Override
        R apply(T t);
    }
}

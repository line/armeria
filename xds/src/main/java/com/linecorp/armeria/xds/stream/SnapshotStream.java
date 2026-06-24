/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.stream;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.SnapshotWatcher;

import io.netty.util.concurrent.EventExecutor;

/**
 * A reactive stream that delivers snapshot values to {@link SnapshotWatcher} subscribers.
 * Subscribers receive the latest value immediately upon subscription (if available)
 * and subsequent updates as they occur.
 *
 * <p>This is a {@link FunctionalInterface} — custom streams can be created with a lambda
 * that receives a watcher and returns a {@link Subscription}:
 * <pre>{@code
 * SnapshotStream<String> stream = watcher -> {
 *     watcher.onUpdate("hello", null);
 *     return Subscription.noop();
 * };
 * }</pre>
 *
 * @param <T> the type of snapshot values delivered by this stream
 */
@UnstableApi
@FunctionalInterface
public interface SnapshotStream<T> {

    /**
     * Subscribes the given watcher to this stream. The watcher will receive the current
     * value (if any) and all subsequent updates.
     *
     * @param watcher the watcher to receive snapshot updates
     * @return a {@link Subscription} that can be closed to unsubscribe
     */
    @CheckReturnValue
    Subscription subscribe(SnapshotWatcher<? super T> watcher);

    /**
     * Returns a new stream that applies the given mapping function to each value
     * emitted by this stream.
     *
     * @param mapper the function to apply to each emitted value
     * @param <R> the type of the mapped values
     */
    default <R> SnapshotStream<R> map(Function<? super T, ? extends R> mapper) {
        requireNonNull(mapper, "mapper");
        return new MapStream<>(this, mapper);
    }

    /**
     * Returns a new stream that, for each value emitted by this stream, subscribes to
     * the inner stream produced by the mapper and emits its values. When this stream
     * emits a new value, the previous inner subscription is closed eagerly.
     *
     * @param mapper the function that produces an inner stream for each value
     * @param <R> the element type of the inner streams
     * @param <O> the type of the inner stream
     */
    default <R, O extends SnapshotStream<? extends R>> SnapshotStream<R> switchMapEager(
            Function<? super T, ? extends O> mapper) {
        requireNonNull(mapper, "mapper");
        return new SwitchMapEagerStream<>(this, mapper);
    }

    /**
     * Returns a stream that combines the latest values from all given streams into a list.
     * The combined stream emits a new list whenever any source stream emits a new value,
     * but only after all source streams have emitted at least one value.
     *
     * @param streams the source streams to combine
     * @param <S> the type of the source streams
     * @param <I> the element type of each source stream
     */
    static <S extends SnapshotStream<I>, I> SnapshotStream<List<I>> combineNLatest(List<S> streams) {
        requireNonNull(streams, "streams");
        return new CombineNLatestStream<>(ImmutableList.copyOf(streams));
    }

    /**
     * Returns a stream that combines the latest values from two streams using the given
     * combiner function. The combined stream emits a new value whenever either source
     * stream emits, but only after both have emitted at least one value.
     *
     * @param a the first source stream
     * @param b the second source stream
     * @param combiner the function to combine the latest values
     * @param <A> the type of the first stream's values
     * @param <B> the type of the second stream's values
     * @param <O> the type of the combined values
     */
    static <A, B, O> SnapshotStream<O> combineLatest(
            SnapshotStream<A> a,
            SnapshotStream<B> b,
            BiFunction<? super A, ? super B, ? extends O> combiner) {
        requireNonNull(a, "a");
        requireNonNull(b, "b");
        requireNonNull(combiner, "combiner");
        return new CombineLatest2Stream<>(a, b, combiner);
    }

    /**
     * Returns a stream that combines the latest values from three streams using the given
     * combiner function. The combined stream emits a new value whenever any source stream
     * emits, but only after all three have emitted at least one value.
     *
     * @param a the first source stream
     * @param b the second source stream
     * @param c the third source stream
     * @param combiner the function to combine the latest values
     * @param <A> the type of the first stream's values
     * @param <B> the type of the second stream's values
     * @param <C> the type of the third stream's values
     * @param <O> the type of the combined values
     */
    static <A, B, C, O> SnapshotStream<O> combineLatest(
            SnapshotStream<A> a,
            SnapshotStream<B> b,
            SnapshotStream<C> c,
            TriFunction<? super A, ? super B, ? super C, ? extends O> combiner) {
        requireNonNull(a, "a");
        requireNonNull(b, "b");
        requireNonNull(c, "c");
        requireNonNull(combiner, "combiner");
        return new CombineLatest3Stream<>(a, b, c, combiner);
    }

    /**
     * Returns a stream that combines the latest values from four streams using the given
     * combiner function. The combined stream emits a new value whenever any source stream
     * emits, but only after all four have emitted at least one value.
     *
     * @param a the first source stream
     * @param b the second source stream
     * @param c the third source stream
     * @param d the fourth source stream
     * @param combiner the function to combine the latest values
     * @param <A> the type of the first stream's values
     * @param <B> the type of the second stream's values
     * @param <C> the type of the third stream's values
     * @param <D> the type of the fourth stream's values
     * @param <O> the type of the combined values
     */
    static <A, B, C, D, O> SnapshotStream<O> combineLatest(
            SnapshotStream<A> a,
            SnapshotStream<B> b,
            SnapshotStream<C> c,
            SnapshotStream<D> d,
            QuadFunction<? super A, ? super B, ? super C, ? super D, ? extends O> combiner) {
        requireNonNull(a, "a");
        requireNonNull(b, "b");
        requireNonNull(c, "c");
        requireNonNull(d, "d");
        requireNonNull(combiner, "combiner");
        return new CombineLatest4Stream<>(a, b, c, d, combiner);
    }

    /**
     * Returns a stream that immediately emits the given value to every subscriber.
     *
     * @param value the value to emit
     * @param <T> the type of the value
     */
    static <T> SnapshotStream<T> just(T value) {
        requireNonNull(value, "value");
        return new StaticSnapshotStream<>(value, null);
    }

    /**
     * Returns a stream that immediately emits an empty {@link Optional} to every subscriber.
     *
     * @param <T> the element type of the optional
     */
    @SuppressWarnings("unchecked")
    static <T> SnapshotStream<Optional<T>> empty() {
        return (SnapshotStream<Optional<T>>) StaticSnapshotStream.EMPTY;
    }

    /**
     * Returns a stream that immediately emits the given error to every subscriber.
     *
     * @param error the error to emit
     * @param <T> the nominal value type
     */
    static <T> SnapshotStream<T> error(Throwable error) {
        requireNonNull(error, "error");
        return new StaticSnapshotStream<>(null, error);
    }

    /**
     * Returns a caching function that deduplicates {@link SnapshotStream} subscriptions by key
     * using reference counting. When multiple subscribers request the same key, they share
     * a single upstream {@link SnapshotStream}. The upstream is created lazily on the first
     * subscription and closed automatically when the last subscriber unsubscribes.
     *
     * <p>Example usage:
     * <pre>{@code
     * Function<String, SnapshotStream<MySnapshot>> cached = SnapshotStream.caching(
     *     name -> createSnapshotStream(name));
     *
     * // Both streams share the same underlying subscription for "foo"
     * SnapshotStream<MySnapshot> stream1 = cached.apply("foo");
     * SnapshotStream<MySnapshot> stream2 = cached.apply("foo");
     * }</pre>
     *
     * @param factory a function that creates a new {@link SnapshotStream} for a given key
     * @param <K> the key type used to identify cached streams
     * @param <T> the type of snapshot values delivered by the cached streams
     * @return a function that returns cached {@link SnapshotStream}s by key
     */
    static <K, T> Function<K, SnapshotStream<T>> caching(
            Function<? super K, ? extends SnapshotStream<T>> factory) {
        requireNonNull(factory, "factory");
        final CachingStream<K, T> cachingStream = new CachingStream<>(factory);
        return cachingStream::subscribe;
    }

    /**
     * Returns a new stream that asserts {@link #subscribe} and {@link Subscription#close()}
     * are called from the given event loop. Throws {@link IllegalStateException} if called
     * from a different thread.
     *
     * @param eventLoop the event loop that subscribe and close must be called from
     */
    default SnapshotStream<T> checkSubscribeOn(EventExecutor eventLoop) {
        requireNonNull(eventLoop, "eventLoop");
        final SnapshotStream<T> self = this;
        return watcher -> {
            checkState(eventLoop.inEventLoop(),
                       "subscribe must be called from the event loop: %s", eventLoop);
            final Subscription sub = self.subscribe(watcher);
            return () -> {
                checkState(eventLoop.inEventLoop(),
                           "close must be called from the event loop: %s", eventLoop);
                sub.close();
            };
        };
    }

    /**
     * Returns a new stream that reschedules all {@link SnapshotWatcher#onUpdate} emissions
     * to the given event loop. Emissions are always rescheduled (no {@code inEventLoop()}
     * shortcut) to guarantee strict FIFO ordering with respect to other event loop tasks.
     *
     * @param eventLoop the event loop to deliver emissions on
     */
    default SnapshotStream<T> rescheduleEventsOn(EventExecutor eventLoop) {
        requireNonNull(eventLoop, "eventLoop");
        final SnapshotStream<T> self = this;
        return watcher -> {
            final RescheduleSubscription<T> sub = new RescheduleSubscription<>(watcher, eventLoop);
            sub.setUpstream(self.subscribe(sub));
            return sub;
        };
    }
}

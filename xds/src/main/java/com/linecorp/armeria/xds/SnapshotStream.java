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

package com.linecorp.armeria.xds;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.xds.CombineLatest3Stream.TriFunction;

@FunctionalInterface
interface SnapshotStream<T> {

    @CheckReturnValue
    Subscription subscribe(SnapshotWatcher<? super T> watcher);

    @FunctionalInterface
    interface Subscription {

        static Subscription noop() {
            return () -> {};
        }

        void close();
    }

    default <R> SnapshotStream<R> map(Function<? super T, ? extends R> mapper) {
        return new MapStream<>(this, mapper);
    }

    default <R, O extends SnapshotStream<? extends R>> SnapshotStream<R> switchMap(
            Function<? super T, ? extends O> mapper) {
        return new SwitchMapStream<>(this, mapper);
    }

    static <S extends SnapshotStream<I>, I> SnapshotStream<List<I>> combineNLatest(List<S> stream) {
        return new CombineNLatestStream<>(ImmutableList.copyOf(stream));
    }

    static <A, B, O> SnapshotStream<O> combineLatest(
            SnapshotStream<A> a,
            SnapshotStream<B> b,
            BiFunction<? super A, ? super B, ? extends O> combiner) {
        return new CombineLatest2Stream<>(a, b, combiner);
    }

    static <A, B, C, O> SnapshotStream<O> combineLatest(
            SnapshotStream<A> a,
            SnapshotStream<B> b,
            SnapshotStream<C> c,
            TriFunction<? super A, ? super B, ? super C, ? extends O> combiner) {
        return new CombineLatest3Stream<>(a, b, c, combiner);
    }

    static <T> SnapshotStream<T> just(T value) {
        return new StaticSnapshotStream<>(value, null);
    }

    @SuppressWarnings("unchecked")
    static <T> SnapshotStream<Optional<T>> empty() {
        return (SnapshotStream<Optional<T>>) StaticSnapshotStream.EMPTY;
    }

    static <T> SnapshotStream<T> error(Throwable error) {
        return new StaticSnapshotStream<>(null, error);
    }
}

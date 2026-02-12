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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.SnapshotStream.Subscription;

@SuppressWarnings("CheckReturnValue")
class StreamSwitchMapTest {

    @Test
    void switchMapBasicFlow() {
        final TestStream<String> upstream = new TestStream<>();
        final List<String> received = new ArrayList<>();

        final SnapshotStream<String> switched = upstream.switchMap(
                value -> SnapshotStream.just(value + "-mapped"));

        switched.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received.add(snapshot);
            }
        });

        upstream.emit("value1", null);
        upstream.emit("value2", null);

        assertThat(received).containsExactly("value1-mapped", "value2-mapped");
    }

    @Test
    void switchMapSwitchesToNewInnerStream() {
        final TestStream<String> upstream = new TestStream<>();
        final TestStream<String> inner1 = new TestStream<>();
        final TestStream<String> inner2 = new TestStream<>();
        final List<String> received = new ArrayList<>();

        final SnapshotStream<String> switched = upstream.switchMap(value -> {
            if ("key1".equals(value)) {
                return inner1;
            } else {
                return inner2;
            }
        });

        switched.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received.add(snapshot);
            }
        });

        upstream.emit("key1", null);
        inner1.emit("inner1-value1", null);
        inner1.emit("inner1-value2", null);

        upstream.emit("key2", null);
        inner2.emit("inner2-value1", null);
        inner1.emit("inner1-value3", null);

        assertThat(received).containsExactly(
                "inner1-value1",
                "inner1-value2",
                "inner2-value1"
        );
    }

    @Test
    void switchMapUnsubscribesFromPreviousInnerStream() {
        final TestStream<String> upstream = new TestStream<>();
        final TestStream<String> inner1 = new TestStream<>();
        final TestStream<String> inner2 = new TestStream<>();

        final SnapshotStream<String> switched = upstream.switchMap(value -> {
            if ("key1".equals(value)) {
                return inner1;
            } else {
                return inner2;
            }
        });

        switched.subscribe((snapshot, error) -> {});

        upstream.emit("key1", null);
        assertThat(inner1.hasWatchers()).isTrue();

        upstream.emit("key2", null);
        assertThat(inner1.hasWatchers()).isFalse();
        assertThat(inner2.hasWatchers()).isTrue();
    }

    @Test
    void switchMapPropagatesUpstreamError() {
        final TestStream<String> upstream = new TestStream<>();
        final List<Throwable> errors = new ArrayList<>();

        final SnapshotStream<String> switched = upstream.switchMap(SnapshotStream::just);

        switched.subscribe((snapshot, error) -> {
            if (error != null) {
                errors.add(error);
            }
        });

        final RuntimeException testError = new RuntimeException("upstream error");
        upstream.emit(null, testError);

        assertThat(errors).containsExactly(testError);
    }

    @Test
    void switchMapPropagatesInnerError() {
        final TestStream<String> upstream = new TestStream<>();
        final TestStream<String> inner = new TestStream<>();
        final List<Throwable> errors = new ArrayList<>();

        final SnapshotStream<String> switched = upstream.switchMap(value -> inner);

        switched.subscribe((snapshot, error) -> {
            if (error != null) {
                errors.add(error);
            }
        });

        upstream.emit("key", null);

        final RuntimeException testError = new RuntimeException("inner error");
        inner.emit(null, testError);

        assertThat(errors).containsExactly(testError);
    }

    @Test
    void switchMapPropagatesMapperException() {
        final TestStream<String> upstream = new TestStream<>();
        final List<Throwable> errors = new ArrayList<>();

        final RuntimeException testError = new RuntimeException("mapper error");
        final SnapshotStream<String> switched = upstream.switchMap(value -> {
            throw testError;
        });

        switched.subscribe((snapshot, error) -> {
            if (error != null) {
                errors.add(error);
            }
        });

        upstream.emit("key", null);

        assertThat(errors).containsExactly(testError);
    }

    @Test
    void switchMapUnsubscribeClosesAllStreams() {
        final TestStream<String> upstream = new TestStream<>();
        final TestStream<String> inner = new TestStream<>();

        final SnapshotStream<String> switched = upstream.switchMap(value -> inner);

        final Subscription subscription = switched.subscribe((snapshot, error) -> {});

        upstream.emit("key", null);

        assertThat(upstream.hasWatchers()).isTrue();
        assertThat(inner.hasWatchers()).isTrue();

        subscription.close();

        assertThat(upstream.hasWatchers()).isFalse();
        assertThat(inner.hasWatchers()).isFalse();
    }

    @Test
    void switchMapMultipleSubscribers() {
        final TestStream<String> upstream = new TestStream<>();
        final List<String> received1 = new ArrayList<>();
        final List<String> received2 = new ArrayList<>();

        final SnapshotStream<String> switched =
                upstream.switchMap(value -> SnapshotStream.just(value + "-mapped"));

        switched.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received1.add(snapshot);
            }
        });
        switched.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received2.add(snapshot);
            }
        });

        upstream.emit("value1", null);

        assertThat(received1).containsExactly("value1-mapped");
        assertThat(received2).containsExactly("value1-mapped");
    }

    @Test
    void switchMapNewSubscriberReceivesLatestValue() {
        final TestStream<String> upstream = new TestStream<>();
        final List<String> received = new ArrayList<>();

        final SnapshotStream<String> switched =
                upstream.switchMap(value -> SnapshotStream.just(value + "-mapped"));

        switched.subscribe((snapshot, error) -> {});

        upstream.emit("value1", null);

        switched.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received.add(snapshot);
            }
        });

        assertThat(received).containsExactly("value1-mapped");
    }

    @Test
    void upstreamEmitsAfterSwitch() {
        final TestStream<String> upstream = new TestStream<>();

        final Deque<TestStream<String>> q = new ArrayDeque<>();

        final SnapshotStream<String> switched = upstream.switchMap(value -> {
            final TestStream<String> testStream = new TestStream<>();
            q.push(testStream);
            for (TestStream<String> stream : q) {
                stream.emit(value, null);
            }
            return testStream;
        });

        final List<String> received = new ArrayList<>();
        final Subscription sub = switched.subscribe((value, error) -> {
            if (value != null) {
                received.add(value);
            }
        });

        for (int i = 0; i < 3; i++) {
            upstream.emit("value1", null);
        }
        assertThat(received).hasSize(3);

        sub.close();
        for (int i = 0; i < 3; i++) {
            upstream.emit("value1", null);
        }

        assertThat(received).hasSize(3);
    }

    @Test
    void switchMapOnlyStartsUpstreamOnceWithMultipleSubscribers() {
        final AtomicInteger upstreamStartCount = new AtomicInteger(0);
        final TestStream<String> upstream = new TestStream<>(() -> {
            upstreamStartCount.incrementAndGet();
            return Subscription.noop();
        });

        final SnapshotStream<String> switched = upstream.switchMap(SnapshotStream::just);

        switched.subscribe((snapshot, error) -> {});
        switched.subscribe((snapshot, error) -> {});

        assertThat(upstreamStartCount.get()).isOne();
    }

    @Test
    void switchMapHandlesStaticStreams() {
        final List<String> received = new ArrayList<>();

        final SnapshotStream<String> stream = SnapshotStream.just("static-value");
        final SnapshotStream<String> switched =
                stream.switchMap(value -> SnapshotStream.just(value + "-mapped"));

        switched.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received.add(snapshot);
            }
        });

        assertThat(received).containsExactly("static-value-mapped");
    }

    @Test
    void switchMapClosesInnerStreamOnUnsubscribe() {
        final TestStream<String> upstream = new TestStream<>();
        final TestStream<String> inner = new TestStream<>();

        final SnapshotStream<String> switched = upstream.switchMap(value -> inner);

        final Subscription sub1 = switched.subscribe((snapshot, error) -> {});
        final Subscription sub2 = switched.subscribe((snapshot, error) -> {});

        upstream.emit("key", null);

        sub1.close();
        assertThat(inner.hasWatchers()).isTrue();

        sub2.close();
        assertThat(inner.hasWatchers()).isFalse();
    }

    @Test
    void emitClosesImmediately() {
        final TestStream<Consumer<Integer>> upstream = new TestStream<>();

        final Deque<TestStream<Consumer<Integer>>> q = new ArrayDeque<>();

        final SnapshotStream<Consumer<Integer>> switched = upstream.switchMap(value -> {
            final TestStream<Consumer<Integer>> testStream = new TestStream<>();
            q.push(testStream);
            for (TestStream<Consumer<Integer>> stream : q) {
                stream.emit(value, null);
            }
            return testStream;
        });

        for (int i = 0; i < 3; i++) {
            final int input = i;
            final SnapshotWatcher<Consumer<Integer>> watcher = (value, error) -> {
                if (value != null) {
                    value.accept(input);
                }
            };
            final Subscription sub = switched.subscribe(watcher);
            upstream.emit(in -> sub.close(), null);
        }
        assertThat(q).hasSizeGreaterThanOrEqualTo(3);
        while (!q.isEmpty()) {
            final TestStream<?> stream = q.poll();
            assertThat(stream.hasWatchers()).isFalse();
        }
    }

    static class TestStream<T> extends RefCountedStream<T> {

        private final Function<SnapshotWatcher<T>, Subscription> onStartFunction;

        TestStream() {
            onStartFunction = ignored -> Subscription::noop;
        }

        TestStream(Supplier<Subscription> onStartFunction) {
            this.onStartFunction = ignored -> onStartFunction.get();
        }

        TestStream(Function<SnapshotWatcher<T>, Subscription> onStartFunction) {
            this.onStartFunction = onStartFunction;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<T> watcher) {
            return onStartFunction.apply(watcher);
        }

        @Override
        protected void emit(@Nullable T value, @Nullable Throwable error) {
            super.emit(value, error);
        }
    }
}

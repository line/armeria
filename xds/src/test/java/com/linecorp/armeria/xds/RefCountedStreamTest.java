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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.SnapshotStream.Subscription;

class RefCountedStreamTest {

    @Test
    void firstSubscriberTriggersOnStart() {
        final AtomicInteger onStartCount = new AtomicInteger(0);
        final TestRefCountedStream stream = new TestRefCountedStream(() -> {
            onStartCount.incrementAndGet();
            return Subscription.noop();
        });

        assertThat(onStartCount.get()).isZero();
        stream.subscribe((snapshot, error) -> {});
        assertThat(onStartCount.get()).isOne();
    }

    @Test
    void secondSubscriberDoesNotTriggerOnStart() {
        final AtomicInteger onStartCount = new AtomicInteger(0);
        final TestRefCountedStream stream = new TestRefCountedStream(() -> {
            onStartCount.incrementAndGet();
            return Subscription.noop();
        });

        stream.subscribe((snapshot, error) -> {});
        stream.subscribe((snapshot, error) -> {});
        assertThat(onStartCount.get()).isOne();
    }

    @Test
    void latestValueSentToNewSubscriber() {
        final TestRefCountedStream stream = new TestRefCountedStream(Subscription::noop);
        final List<String> received1 = new ArrayList<>();
        final List<String> received2 = new ArrayList<>();

        stream.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received1.add(snapshot);
            }
        });

        stream.emit("value1", null);

        stream.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received2.add(snapshot);
            }
        });

        assertThat(received1).containsExactly("value1");
        assertThat(received2).containsExactly("value1");
    }

    @Test
    void emitBroadcastsToAllWatchers() {
        final TestRefCountedStream stream = new TestRefCountedStream(Subscription::noop);
        final List<String> received1 = new ArrayList<>();
        final List<String> received2 = new ArrayList<>();

        stream.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received1.add(snapshot);
            }
        });
        stream.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received2.add(snapshot);
            }
        });

        stream.emit("value1", null);
        stream.emit("value2", null);

        assertThat(received1).containsExactly("value1", "value2");
        assertThat(received2).containsExactly("value1", "value2");
    }

    @Test
    void emitWithErrorBroadcastsToAllWatchers() {
        final TestRefCountedStream stream = new TestRefCountedStream(Subscription::noop);
        final List<Throwable> errors1 = new ArrayList<>();
        final List<Throwable> errors2 = new ArrayList<>();

        stream.subscribe((snapshot, error) -> {
            if (error != null) {
                errors1.add(error);
            }
        });
        stream.subscribe((snapshot, error) -> {
            if (error != null) {
                errors2.add(error);
            }
        });

        final Throwable error = new RuntimeException("test error");
        stream.emit(null, error);

        assertThat(errors1).containsExactly(error);
        assertThat(errors2).containsExactly(error);
    }

    @Test
    void emitNotifiesWatchersInSubscriptionOrder() {
        final TestRefCountedStream stream = new TestRefCountedStream(Subscription::noop);
        final List<String> order = new ArrayList<>();

        stream.subscribe((snapshot, error) -> order.add("first"));
        stream.subscribe((snapshot, error) -> order.add("second"));

        stream.emit("value1", null);

        assertThat(order).containsExactly("first", "second");
    }

    @Test
    void emitAllowsWatcherRemovalDuringCallback() {
        final TestRefCountedStream stream = new TestRefCountedStream(Subscription::noop);
        final AtomicInteger updates = new AtomicInteger();
        final AtomicReference<Subscription> sub1 = new AtomicReference<>();

        final Subscription subscription1 = stream.subscribe((snapshot, error) -> {
            updates.incrementAndGet();
            sub1.get().close();
        });
        sub1.set(subscription1);
        stream.subscribe((snapshot, error) -> updates.incrementAndGet());

        assertThatCode(() -> stream.emit("value1", null)).doesNotThrowAnyException();
        assertThat(updates.get()).isEqualTo(2);

        stream.emit("value2", null);
        assertThat(updates.get()).isEqualTo(3);
    }

    @Test
    void unsubscribeRemovesWatcher() {
        final TestRefCountedStream stream = new TestRefCountedStream(Subscription::noop);
        final List<String> received = new ArrayList<>();

        final Subscription subscription = stream.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received.add(snapshot);
            }
        });

        stream.emit("value1", null);
        subscription.close();
        stream.emit("value2", null);

        assertThat(received).containsExactly("value1");
    }

    @Test
    void lastUnsubscribeTriggersOnStop() {
        final AtomicInteger onStopCount = new AtomicInteger(0);
        final TestRefCountedStream stream = new TestRefCountedStream(Subscription::noop,
                                                                     onStopCount::incrementAndGet);

        final Subscription sub1 = stream.subscribe((snapshot, error) -> {});
        final Subscription sub2 = stream.subscribe((snapshot, error) -> {});

        assertThat(onStopCount.get()).isZero();

        sub1.close();
        assertThat(onStopCount.get()).isZero();

        sub2.close();
        assertThat(onStopCount.get()).isOne();
    }

    @Test
    void lastUnsubscribeClosesUpstreamSubscription() {
        final AtomicBoolean upstreamClosed = new AtomicBoolean(false);
        final TestRefCountedStream stream = new TestRefCountedStream(() -> () -> upstreamClosed.set(true));

        final Subscription sub1 = stream.subscribe((snapshot, error) -> {});
        final Subscription sub2 = stream.subscribe((snapshot, error) -> {});

        assertThat(upstreamClosed.get()).isFalse();

        sub1.close();
        assertThat(upstreamClosed.get()).isFalse();

        sub2.close();
        assertThat(upstreamClosed.get()).isTrue();
    }

    @Test
    void hasWatchersReturnsTrueWhenWatchersExist() {
        final TestRefCountedStream stream = new TestRefCountedStream(Subscription::noop);

        assertThat(stream.hasWatchers()).isFalse();

        final Subscription sub = stream.subscribe((snapshot, error) -> {});
        assertThat(stream.hasWatchers()).isTrue();

        sub.close();
        assertThat(stream.hasWatchers()).isFalse();
    }

    @Test
    void onStartExceptionHandling() {
        final RuntimeException testException = new RuntimeException("onStart error");
        final TestRefCountedStream stream = new TestRefCountedStream(() -> {
            throw testException;
        });

        final List<Throwable> receivedErrors = new ArrayList<>();
        final Subscription subscription = stream.subscribe((snapshot, error) -> {
            if (error != null) {
                receivedErrors.add(error);
            }
        });

        assertThat(receivedErrors).containsExactly(testException);
        assertThat(subscription).isNotNull();
        assertThat(stream.hasWatchers()).isFalse();
    }

    @Test
    void emitUpdatesLatestValue() {
        final TestRefCountedStream stream = new TestRefCountedStream(Subscription::noop);

        stream.subscribe((snapshot, error) -> {});
        stream.emit("value1", null);

        final List<String> received = new ArrayList<>();
        stream.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                received.add(snapshot);
            }
        });

        assertThat(received).containsExactly("value1");
    }

    @Test
    void multipleUnsubscribesSafe() {
        final TestRefCountedStream stream = new TestRefCountedStream(Subscription::noop);
        final Subscription subscription = stream.subscribe((snapshot, error) -> {});

        subscription.close();
        subscription.close();

        assertThat(stream.hasWatchers()).isFalse();
    }

    @Test
    void resubscribeAfterAllUnsubscribe() {
        final AtomicInteger onStartCount = new AtomicInteger(0);
        final AtomicInteger onStopCount = new AtomicInteger(0);
        final TestRefCountedStream stream = new TestRefCountedStream(() -> {
            onStartCount.incrementAndGet();
            return Subscription.noop();
        }, onStopCount::incrementAndGet);

        final Subscription sub1 = stream.subscribe((snapshot, error) -> {});
        sub1.close();

        assertThat(onStartCount.get()).isOne();
        assertThat(onStopCount.get()).isOne();

        stream.subscribe((snapshot, error) -> {});

        assertThat(onStartCount.get()).isEqualTo(2);
        assertThat(onStopCount.get()).isOne();
    }

    private static class TestRefCountedStream extends RefCountedStream<String> {

        private final Supplier<Subscription> onStartFunction;
        private final Runnable onStopCallback;

        TestRefCountedStream(Supplier<Subscription> onStartFunction) {
            this.onStartFunction = onStartFunction;
            onStopCallback = () -> {};
        }

        TestRefCountedStream(Supplier<Subscription> onStartFunction,
                             Runnable onStopCallback) {
            this.onStartFunction = onStartFunction;
            this.onStopCallback = onStopCallback;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<String> watcher) {
            return onStartFunction.get();
        }

        @Override
        protected void onStop() {
            onStopCallback.run();
        }

        @Override
        protected void emit(@Nullable String value, @Nullable Throwable error) {
            super.emit(value, error);
        }
    }
}

/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.common.util.TimeoutMode.EXTEND;
import static com.linecorp.armeria.common.util.TimeoutMode.SET_FROM_NOW;
import static com.linecorp.armeria.common.util.TimeoutMode.SET_FROM_START;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.internal.common.TimeoutScheduler.State;
import com.linecorp.armeria.internal.common.TimeoutScheduler.TimeoutTask;

import io.netty.util.concurrent.EventExecutor;

class TimeoutSchedulerTest {

    private static final EventExecutor eventExecutor = CommonPools.workerGroup().next();

    private static final TimeoutException timeoutCause = new TimeoutException();

    private static final TimeoutTask noopTimeoutTask = new TimeoutTask() {
        @Override
        public boolean canSchedule() {
            return true;
        }

        @Override
        public void run(Throwable cause) {}
    };

    private static void executeInEventLoop(long initTimeoutNanos, Consumer<TimeoutScheduler> task) {
        final AtomicBoolean completed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            final TimeoutScheduler timeoutScheduler = new TimeoutScheduler(0);
            timeoutScheduler.init(eventExecutor, noopTimeoutTask, initTimeoutNanos, timeoutCause);
            task.accept(timeoutScheduler);
            completed.set(true);
        });
        await().untilTrue(completed);
    }

    @Test
    void extendTimeout() {
        final long initTimeoutNanos = MILLISECONDS.toNanos(1000);
        final long adjustmentNanos = MILLISECONDS.toNanos(200);
        final long tolerance = MILLISECONDS.toNanos(100);

        executeInEventLoop(initTimeoutNanos, timeoutScheduler -> {
            final long startTimeNanos = timeoutScheduler.startTimeNanos();

            timeoutScheduler.setTimeoutNanos(EXTEND, adjustmentNanos);
            final long passedNanos = System.nanoTime() - startTimeNanos;
            assertThat(timeoutScheduler.timeoutNanos()).isBetween(
                    initTimeoutNanos + adjustmentNanos - passedNanos - tolerance,
                    initTimeoutNanos + adjustmentNanos - passedNanos + tolerance);

            final long adjustmentNanos2 = MILLISECONDS.toNanos(-200);
            timeoutScheduler.setTimeoutNanos(EXTEND, adjustmentNanos2);
            final long passedMillis2 = System.nanoTime() - startTimeNanos;
            assertThat(timeoutScheduler.timeoutNanos()).isBetween(
                    initTimeoutNanos + adjustmentNanos + adjustmentNanos2 - passedMillis2 - tolerance,
                    initTimeoutNanos + adjustmentNanos + adjustmentNanos2 - passedMillis2 + tolerance);
        });
    }

    @Test
    void setTimeoutFromNow() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(500));
            assertTimeoutWithTolerance(timeoutScheduler.timeoutNanos(), MILLISECONDS.toNanos(500));
        });
    }

    @Test
    void setTimeoutFromNowZero() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            assertThatThrownBy(() -> timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeoutNanos:");
        });
    }

    @Test
    void setTimeoutFromNowMultipleNonZero() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(500));
        });
    }

    @Test
    void cancelTimeoutBeforeDeadline() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            timeoutScheduler.clearTimeout();
            assertThat(timeoutScheduler.isFinished()).isFalse();
        });
    }

    @Test
    void cancelTimeoutAfterDeadline() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.finishNow();
            timeoutScheduler.clearTimeout();
            assertThat(timeoutScheduler.isFinished()).isTrue();
        });
    }

    @Test
    void cancelTimeoutBySettingTimeoutZero() {
        executeInEventLoop(1000, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(SET_FROM_START, 0);
            assertThat(timeoutScheduler.state()).isEqualTo(State.INACTIVE);
        });
    }

    @Test
    void scheduleTimeoutWhenFinished() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.finishNow();
            assertThat(timeoutScheduler.isFinished()).isTrue();
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            assertThat(timeoutScheduler.isFinished()).isTrue();
        });
    }

    @Test
    void extendTimeoutWhenScheduled() {
        executeInEventLoop(0, timeoutScheduler -> {
            final long timeoutNanos = MILLISECONDS.toNanos(1000);
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, timeoutNanos);
            final long currentTimeoutNanos = timeoutScheduler.timeoutNanos();
            assertTimeoutWithTolerance(currentTimeoutNanos, timeoutNanos);
            timeoutScheduler.setTimeoutNanos(EXTEND, timeoutNanos);
            assertTimeoutWithTolerance(timeoutScheduler.timeoutNanos(), currentTimeoutNanos + timeoutNanos);
        });
    }

    @Test
    void extendTimeoutWhenFinished() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.finishNow();
            assertThat(timeoutScheduler.isFinished()).isTrue();
            timeoutScheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(1000));
            assertThat(timeoutScheduler.isFinished()).isTrue();
        });
    }

    @Test
    void cancelTimeoutWhenScheduled() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            timeoutScheduler.clearTimeout();
        });
    }

    @Test
    void cancelTimeoutWhenFinished() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.finishNow();
            timeoutScheduler.clearTimeout();
            assertThat(timeoutScheduler.isFinished()).isTrue();
        });
    }

    @Test
    void finishWhenFinished() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.finishNow();
            assertThat(timeoutScheduler.isFinished()).isTrue();
            timeoutScheduler.finishNow();
            assertThat(timeoutScheduler.isFinished()).isTrue();
        });
    }

    @Test
    void setTimeoutFromStartAfterClear() {
        final AtomicBoolean completed = new AtomicBoolean();

        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.clearTimeout();
            final long newTimeoutNanos = MILLISECONDS.toNanos(1123);
            timeoutScheduler.setTimeoutNanos(SET_FROM_START, newTimeoutNanos);
            assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(newTimeoutNanos);

            eventExecutor.schedule(() -> {
                assertThat(timeoutScheduler.isFinished()).isTrue();
                completed.set(true);
            }, 2500, MILLISECONDS);
        });

        await().untilTrue(completed);
    }

    @Test
    void setTimeoutFromStartAfterClearAndFinished() {
        final AtomicBoolean completed = new AtomicBoolean();
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.clearTimeout();
            eventExecutor.schedule(() -> {
                final long newTimeoutNanos = MILLISECONDS.toNanos(1123);
                timeoutScheduler.setTimeoutNanos(SET_FROM_START, newTimeoutNanos);
                assertThat(timeoutScheduler.isFinished()).isTrue();
                completed.set(true);
            }, 2000, MILLISECONDS);
        });
        await().untilTrue(completed);
    }

    @Test
    void whenTimingOut() {
        final AtomicReference<CompletableFuture<Void>> timeoutFutureRef = new AtomicReference<>();
        final AtomicReference<TimeoutScheduler> timeoutSchedulerRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean passed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            final TimeoutScheduler timeoutScheduler = new TimeoutScheduler(0);
            final TimeoutTask timeoutTask = new TimeoutTask() {
                @Override
                public boolean canSchedule() {
                    return true;
                }

                @Override
                public void run(Throwable cause) {
                    assertThat(timeoutScheduler.whenTimingOut()).isDone();
                    assertThat(timeoutScheduler.isFinished()).isTrue();
                    assertThat(timeoutScheduler.whenTimedOut()).isNotDone();
                    passed.set(true);
                }
            };
            timeoutScheduler.init(eventExecutor, timeoutTask, 0, timeoutCause);

            assertThat(timeoutScheduler.isFinished()).isFalse();

            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            assertThat(timeoutScheduler.state()).isEqualTo(State.SCHEDULED);

            timeoutSchedulerRef.set(timeoutScheduler);
            timeoutFutureRef.set(timeoutScheduler.whenTimedOut());
            completed.set(true);
        });

        await().untilTrue(passed);
        await().untilTrue(completed);
        timeoutFutureRef.get().join();
        assertThat(timeoutSchedulerRef.get().state()).isEqualTo(State.FINISHED);
    }

    @Test
    void whenTimedOut() {
        final AtomicReference<CompletableFuture<Void>> timeoutFutureRef = new AtomicReference<>();
        executeInEventLoop(0, timeoutScheduler -> {
            final CompletableFuture<Void> timeoutFuture = timeoutScheduler.whenTimedOut();
            assertThat(timeoutFuture).isNotDone();
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            timeoutFutureRef.set(timeoutFuture);
        });
        timeoutFutureRef.get().join();
    }

    @Test
    void whenTimingOutAndWhenTimedOutAreCancelled() {
        final AtomicReference<CompletableFuture<Void>> whenTimingOutFutureRef = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Void>> whenTimedOutFutureRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        executeInEventLoop(0, timeoutScheduler -> {
            whenTimingOutFutureRef.set(timeoutScheduler.whenTimingOut());
            whenTimedOutFutureRef.set(timeoutScheduler.whenTimedOut());
            timeoutScheduler.finishNow(new RuntimeException());
            assertThat(timeoutScheduler.isFinished()).isTrue();
            completed.set(true);
        });
        await().untilTrue(completed);
        assertThatThrownBy(() -> whenTimingOutFutureRef.get().join()).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> whenTimedOutFutureRef.get().join()).isInstanceOf(CancellationException.class);
    }

    @Test
    void pendingTimeout() {
        final TimeoutScheduler timeoutScheduler = new TimeoutScheduler(1000);
        timeoutScheduler.setTimeoutNanos(EXTEND, 1000);
        assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(2000);
        timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, 1000);
        assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(1000);

        timeoutScheduler.clearTimeout(false);
        assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(1000);
        timeoutScheduler.clearTimeout();
        assertThat(timeoutScheduler.timeoutNanos()).isZero();

        timeoutScheduler.setTimeoutNanos(SET_FROM_START, 3000);
        assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(3000);
    }

    @Test
    void evaluatePendingTimeout() {
        final AtomicBoolean completed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            TimeoutScheduler timeoutScheduler = new TimeoutScheduler(MILLISECONDS.toNanos(1000));
            timeoutScheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(1000));
            timeoutScheduler.init(eventExecutor, noopTimeoutTask, 0, timeoutCause);
            assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(2000));

            timeoutScheduler = new TimeoutScheduler(MILLISECONDS.toNanos(1000));
            timeoutScheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(2000));
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            timeoutScheduler.init(eventExecutor, noopTimeoutTask, 0, timeoutCause);
            assertTimeoutWithTolerance(timeoutScheduler.timeoutNanos(), MILLISECONDS.toNanos(1000));

            timeoutScheduler = new TimeoutScheduler(MILLISECONDS.toNanos(1000));
            timeoutScheduler.clearTimeout(false);
            timeoutScheduler.init(eventExecutor, noopTimeoutTask, 0, timeoutCause);
            assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(1000));

            timeoutScheduler = new TimeoutScheduler(MILLISECONDS.toNanos(1000));
            timeoutScheduler.clearTimeout();
            timeoutScheduler.init(eventExecutor, noopTimeoutTask, 0, timeoutCause);
            assertThat(timeoutScheduler.timeoutNanos()).isZero();

            timeoutScheduler = new TimeoutScheduler(MILLISECONDS.toNanos(1000));
            timeoutScheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(2000));
            timeoutScheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            timeoutScheduler.setTimeoutNanos(SET_FROM_START, MILLISECONDS.toNanos(10000));
            timeoutScheduler.init(eventExecutor, noopTimeoutTask, 0, timeoutCause);
            assertTimeoutWithTolerance(timeoutScheduler.timeoutNanos(), MILLISECONDS.toNanos(10000));
            completed.set(true);
        });
        await().untilTrue(completed);
    }

    @Test
    void initializeOnlyOnce() {
        final AtomicBoolean completed = new AtomicBoolean();
        final TimeoutScheduler timeoutScheduler = new TimeoutScheduler(0);
        eventExecutor.execute(() -> {
            timeoutScheduler.init(eventExecutor, noopTimeoutTask, MILLISECONDS.toNanos(100), timeoutCause);
            assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(100));

            timeoutScheduler.init(eventExecutor, noopTimeoutTask, MILLISECONDS.toNanos(1000), timeoutCause);
            assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(100));
            completed.set(true);
        });

        await().untilTrue(completed);
    }

    static void assertTimeoutWithTolerance(long actualNanos, long expectedNanos) {
        assertThat(actualNanos)
                .isCloseTo(expectedNanos, Offset.offset(MILLISECONDS.toNanos(200)));
    }
}

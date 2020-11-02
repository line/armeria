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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.internal.common.CancellationScheduler.CancellationTask;
import com.linecorp.armeria.internal.common.CancellationScheduler.State;

import io.netty.util.concurrent.EventExecutor;

class CancellationSchedulerTest {

    private static final EventExecutor eventExecutor = CommonPools.workerGroup().next();

    private static final TimeoutException cancellationCause = new TimeoutException();

    private static final CancellationTask noopTask = new CancellationTask() {
        @Override
        public boolean canSchedule() {
            return true;
        }

        @Override
        public void run(Throwable cause) {}
    };

    private static void executeInEventLoop(long initTimeoutNanos, Consumer<CancellationScheduler> task) {
        final AtomicBoolean completed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            final CancellationScheduler scheduler = new CancellationScheduler(0);
            scheduler.init(eventExecutor, noopTask, initTimeoutNanos, cancellationCause);
            task.accept(scheduler);
            completed.set(true);
        });
        await().untilTrue(completed);
    }

    @Test
    void extendTimeout() {
        final long initTimeoutNanos = MILLISECONDS.toNanos(1000);
        final long extendNanos = MILLISECONDS.toNanos(200);
        final long extendNanos2 = MILLISECONDS.toNanos(-200);
        final long tolerance = MILLISECONDS.toNanos(100);

        executeInEventLoop(initTimeoutNanos, scheduler -> {
            final long startTimeNanos = scheduler.startTimeNanos();

            scheduler.setTimeoutNanos(EXTEND, extendNanos);
            final long passedNanos = System.nanoTime() - startTimeNanos;
            assertTimeoutWithTolerance(scheduler.timeoutNanos(), initTimeoutNanos + extendNanos - passedNanos,
                                       tolerance);

            scheduler.setTimeoutNanos(EXTEND, extendNanos2);
            final long passedMillis2 = System.nanoTime() - startTimeNanos;
            assertTimeoutWithTolerance(scheduler.timeoutNanos(),
                                       initTimeoutNanos + extendNanos + extendNanos2 - passedMillis2,
                                       tolerance);
        });
    }

    @Test
    void setTimeoutFromNow() {
        executeInEventLoop(0, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(500));
            assertTimeoutWithTolerance(scheduler.timeoutNanos(), MILLISECONDS.toNanos(500));
        });
    }

    @Test
    void setTimeoutFromNowZero() {
        executeInEventLoop(0, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            assertThatThrownBy(() -> scheduler.setTimeoutNanos(SET_FROM_NOW, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeoutNanos:");
        });
    }

    @Test
    void setTimeoutFromNowMultipleNonZero() {
        executeInEventLoop(0, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(500));
        });
    }

    @Test
    void cancelTimeoutBeforeDeadline() {
        executeInEventLoop(0, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.clearTimeout();
            assertThat(scheduler.isFinished()).isFalse();
        });
    }

    @Test
    void cancelTimeoutAfterDeadline() {
        executeInEventLoop(0, scheduler -> {
            scheduler.finishNow();
            scheduler.clearTimeout();
            assertThat(scheduler.isFinished()).isTrue();
        });
    }

    @Test
    void cancelTimeoutBySettingTimeoutZero() {
        executeInEventLoop(1000, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_START, 0);
            assertThat(scheduler.state()).isEqualTo(State.INACTIVE);
        });
    }

    @Test
    void scheduleTimeoutWhenFinished() {
        executeInEventLoop(0, scheduler -> {
            scheduler.finishNow();
            assertThat(scheduler.isFinished()).isTrue();
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            assertThat(scheduler.isFinished()).isTrue();
        });
    }

    @Test
    void extendTimeoutWhenScheduled() {
        executeInEventLoop(0, scheduler -> {
            final long timeoutNanos = MILLISECONDS.toNanos(1000);
            scheduler.setTimeoutNanos(SET_FROM_NOW, timeoutNanos);
            final long currentTimeoutNanos = scheduler.timeoutNanos();
            assertTimeoutWithTolerance(currentTimeoutNanos, timeoutNanos);
            scheduler.setTimeoutNanos(EXTEND, timeoutNanos);
            assertTimeoutWithTolerance(scheduler.timeoutNanos(), currentTimeoutNanos + timeoutNanos);
        });
    }

    @Test
    void extendTimeoutWhenFinished() {
        executeInEventLoop(0, scheduler -> {
            scheduler.finishNow();
            assertThat(scheduler.isFinished()).isTrue();
            scheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(1000));
            assertThat(scheduler.isFinished()).isTrue();
        });
    }

    @Test
    void cancelTimeoutWhenScheduled() {
        executeInEventLoop(0, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.clearTimeout();
        });
    }

    @Test
    void cancelTimeoutWhenFinished() {
        executeInEventLoop(0, scheduler -> {
            scheduler.finishNow();
            scheduler.clearTimeout();
            assertThat(scheduler.isFinished()).isTrue();
        });
    }

    @Test
    void finishWhenFinished() {
        executeInEventLoop(0, scheduler -> {
            scheduler.finishNow();
            assertThat(scheduler.isFinished()).isTrue();
            scheduler.finishNow();
            assertThat(scheduler.isFinished()).isTrue();
        });
    }

    @Test
    void setTimeoutFromStartAfterClear() {
        final AtomicBoolean completed = new AtomicBoolean();

        executeInEventLoop(0, scheduler -> {
            scheduler.clearTimeout();
            final long newTimeoutNanos = MILLISECONDS.toNanos(1123);
            scheduler.setTimeoutNanos(SET_FROM_START, newTimeoutNanos);
            assertThat(scheduler.timeoutNanos()).isEqualTo(newTimeoutNanos);

            eventExecutor.schedule(() -> {
                assertThat(scheduler.isFinished()).isTrue();
                completed.set(true);
            }, 2500, MILLISECONDS);
        });

        await().untilTrue(completed);
    }

    @Test
    void setTimeoutFromStartAfterClearAndFinished() {
        final AtomicBoolean completed = new AtomicBoolean();
        executeInEventLoop(0, scheduler -> {
            scheduler.clearTimeout();
            eventExecutor.schedule(() -> {
                final long newTimeoutNanos = MILLISECONDS.toNanos(1123);
                scheduler.setTimeoutNanos(SET_FROM_START, newTimeoutNanos);
                assertThat(scheduler.isFinished()).isTrue();
                completed.set(true);
            }, 2000, MILLISECONDS);
        });
        await().untilTrue(completed);
    }

    @Test
    void cancellationCause() {
        executeInEventLoop(0, scheduler -> {
            scheduler.finishNow(new IllegalStateException());
            assertThat(scheduler.isFinished()).isTrue();
            assertThat(scheduler.cause()).isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void whenTimingOutAndWhenTimedOut() {
        final AtomicReference<CancellationScheduler> schedulerRef = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Void>> whenTimedOutRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean passed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            final CancellationScheduler scheduler = new CancellationScheduler(0);
            final CancellationTask task = new CancellationTask() {
                @Override
                public boolean canSchedule() {
                    return true;
                }

                @Override
                public void run(Throwable cause) {
                    assertThat(cause).isEqualTo(cancellationCause);
                    assertThat(scheduler.whenTimingOut()).isDone();
                    assertThat(scheduler.isFinished()).isTrue();
                    assertThat(scheduler.whenTimedOut()).isNotDone();
                    passed.set(true);
                }
            };
            scheduler.init(eventExecutor, task, 0, cancellationCause);
            assertThat(scheduler.isFinished()).isFalse();

            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            assertThat(scheduler.state()).isEqualTo(State.SCHEDULED);

            schedulerRef.set(scheduler);
            whenTimedOutRef.set(scheduler.whenTimedOut());
            completed.set(true);
        });
        await().untilTrue(passed);
        await().untilTrue(completed);
        whenTimedOutRef.get().join();
        assertThat(schedulerRef.get().isFinished()).isTrue();
    }

    @Test
    void whenTimingOutAndWhenTimedOut2() {
        final AtomicReference<CompletableFuture<Void>> whenTimingOutRef = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Void>> whenTimedOutRef = new AtomicReference<>();
        executeInEventLoop(0, scheduler -> {
            final CompletableFuture<Void> whenTimingOut = scheduler.whenTimingOut();
            final CompletableFuture<Void> whenTimedOut = scheduler.whenTimedOut();
            assertThat(whenTimingOut).isNotDone();
            assertThat(whenTimedOut).isNotDone();
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            whenTimingOutRef.set(whenTimingOut);
            whenTimedOutRef.set(whenTimedOut);
        });
        whenTimingOutRef.get().join();
        whenTimedOutRef.get().join();
    }

    @Test
    void whenCancellingAndWhenCancelled() {
        final AtomicReference<CancellationScheduler> schedulerRef = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Throwable>> whenCancellingRef = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Throwable>> whenCancelledRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean passed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            final CancellationScheduler scheduler = new CancellationScheduler(0);
            final CancellationTask task = new CancellationTask() {
                @Override
                public boolean canSchedule() {
                    return true;
                }

                @Override
                public void run(Throwable cause) {
                    assertThat(cause).isEqualTo(cancellationCause);
                    assertThat(scheduler.whenCancelling()).isDone();
                    assertThat(scheduler.isFinished()).isTrue();
                    assertThat(scheduler.whenCancelled()).isNotDone();
                    passed.set(true);
                }
            };
            scheduler.init(eventExecutor, task, 0, cancellationCause);
            assertThat(scheduler.isFinished()).isFalse();

            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            assertThat(scheduler.state()).isEqualTo(State.SCHEDULED);

            schedulerRef.set(scheduler);
            whenCancellingRef.set(scheduler.whenCancelling());
            whenCancelledRef.set(scheduler.whenCancelled());
            completed.set(true);
        });
        await().untilTrue(passed);
        await().untilTrue(completed);
        assertThat(whenCancellingRef.get().join()).isEqualTo(cancellationCause);
        assertThat(whenCancelledRef.get().join()).isEqualTo(cancellationCause);
        assertThat(schedulerRef.get().isFinished()).isTrue();
    }

    @Test
    void pendingTimeout() {
        final CancellationScheduler scheduler = new CancellationScheduler(1000);
        scheduler.setTimeoutNanos(EXTEND, 1000);
        assertThat(scheduler.timeoutNanos()).isEqualTo(2000);
        scheduler.setTimeoutNanos(SET_FROM_NOW, 1000);
        assertThat(scheduler.timeoutNanos()).isEqualTo(1000);

        scheduler.clearTimeout(false);
        assertThat(scheduler.timeoutNanos()).isEqualTo(1000);
        scheduler.clearTimeout();
        assertThat(scheduler.timeoutNanos()).isZero();

        scheduler.setTimeoutNanos(SET_FROM_START, 3000);
        assertThat(scheduler.timeoutNanos()).isEqualTo(3000);
    }

    @Test
    void evaluatePendingTimeout() {
        final AtomicBoolean completed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            CancellationScheduler scheduler = new CancellationScheduler(MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(1000));
            scheduler.init(eventExecutor, noopTask, 0, cancellationCause);
            assertThat(scheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(2000));

            scheduler = new CancellationScheduler(MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(2000));
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.init(eventExecutor, noopTask, 0, cancellationCause);
            assertTimeoutWithTolerance(scheduler.timeoutNanos(), MILLISECONDS.toNanos(1000));

            scheduler = new CancellationScheduler(MILLISECONDS.toNanos(1000));
            scheduler.clearTimeout(false);
            scheduler.init(eventExecutor, noopTask, 0, cancellationCause);
            assertThat(scheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(1000));

            scheduler = new CancellationScheduler(MILLISECONDS.toNanos(1000));
            scheduler.clearTimeout();
            scheduler.init(eventExecutor, noopTask, 0, cancellationCause);
            assertThat(scheduler.timeoutNanos()).isZero();

            scheduler = new CancellationScheduler(MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(2000));
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(SET_FROM_START, MILLISECONDS.toNanos(10000));
            scheduler.init(eventExecutor, noopTask, 0, cancellationCause);
            assertTimeoutWithTolerance(scheduler.timeoutNanos(), MILLISECONDS.toNanos(10000));
            completed.set(true);
        });
        await().untilTrue(completed);
    }

    @Test
    void initializeOnlyOnce() {
        final AtomicBoolean completed = new AtomicBoolean();
        final CancellationScheduler scheduler = new CancellationScheduler(0);
        eventExecutor.execute(() -> {
            scheduler.init(eventExecutor, noopTask, MILLISECONDS.toNanos(100), cancellationCause);
            assertThat(scheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(100));

            scheduler.init(eventExecutor, noopTask, MILLISECONDS.toNanos(1000), cancellationCause);
            assertThat(scheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(100));
            completed.set(true);
        });

        await().untilTrue(completed);
    }

    static void assertTimeoutWithTolerance(long actualNanos, long expectedNanos) {
        assertTimeoutWithTolerance(actualNanos, expectedNanos, MILLISECONDS.toNanos(200));
    }

    static void assertTimeoutWithTolerance(long actualNanos, long expectedNanos, long toleranceNanos) {
        assertThat(actualNanos).isCloseTo(expectedNanos, Offset.offset(toleranceNanos));
    }
}

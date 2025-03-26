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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.internal.common.CancellationScheduler.CancellationTask;
import com.linecorp.armeria.internal.common.CancellationScheduler.State;
import com.linecorp.armeria.server.RequestTimeoutException;

import io.netty.util.concurrent.EventExecutor;

class CancellationSchedulerTest {

    private static final EventExecutor eventExecutor = CommonPools.workerGroup().next();

    private static final CancellationTask noopTask = cause -> {};

    private static void executeInEventLoop(long initTimeoutNanos,
                                           Consumer<DefaultCancellationScheduler> task) {
        final AtomicBoolean completed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            final DefaultCancellationScheduler scheduler = new DefaultCancellationScheduler(initTimeoutNanos);
            scheduler.initAndStart(eventExecutor, noopTask);
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
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(500));
            assertTimeoutWithTolerance(scheduler.timeoutNanos(), MILLISECONDS.toNanos(500));
        });
    }

    @Test
    void setTimeoutFromNowZero() {
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            assertThatThrownBy(() -> scheduler.setTimeoutNanos(SET_FROM_NOW, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeoutNanos:");
        });
    }

    @Test
    void setTimeoutFromNowMultipleNonZero() {
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(500));
        });
    }

    @Test
    void cancelTimeoutBeforeDeadline() {
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.clearTimeout();
            assertThat(scheduler.isFinished()).isFalse();
        });
    }

    @Test
    void cancelTimeoutAfterDeadline() {
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.finishNow();
            scheduler.clearTimeout();
            assertThat(scheduler.isFinished()).isTrue();
        });
    }

    @Test
    void cancelTimeoutBySettingTimeoutZero() {
        executeInEventLoop(1000, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_START, Long.MAX_VALUE);
            assertThat(scheduler.state()).isEqualTo(State.SCHEDULED);
        });
    }

    @Test
    void scheduleTimeoutWhenFinished() {
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.finishNow();
            assertThat(scheduler.isFinished()).isTrue();
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            assertThat(scheduler.isFinished()).isTrue();
        });
    }

    @Test
    void extendTimeoutWhenScheduled() {
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
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
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.finishNow();
            assertThat(scheduler.isFinished()).isTrue();
            scheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(1000));
            assertThat(scheduler.isFinished()).isTrue();
        });
    }

    @Test
    void cancelTimeoutWhenScheduled() {
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.clearTimeout();
        });
    }

    @Test
    void cancelTimeoutWhenFinished() {
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.finishNow();
            scheduler.clearTimeout();
            assertThat(scheduler.isFinished()).isTrue();
        });
    }

    @Test
    void finishWhenFinished() {
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.finishNow();
            assertThat(scheduler.isFinished()).isTrue();
            scheduler.finishNow();
            assertThat(scheduler.isFinished()).isTrue();
        });
    }

    @Test
    void setTimeoutFromStartAfterClear() {
        final AtomicBoolean completed = new AtomicBoolean();

        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
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
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
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
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            scheduler.finishNow(new IllegalStateException());
            assertThat(scheduler.isFinished()).isTrue();
            assertThat(scheduler.cause()).isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void whenTimingOutAndWhenTimedOut() {
        final AtomicReference<CancellationScheduler> schedulerRef = new AtomicReference<>();
        final AtomicReference<CompletableFuture<?>> whenTimedOutRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean passed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            final DefaultCancellationScheduler scheduler = new DefaultCancellationScheduler(Long.MAX_VALUE);
            final CancellationTask task = new CancellationTask() {
                @Override
                public boolean canSchedule() {
                    return true;
                }

                @Override
                public void run(Throwable cause) {
                    assertThat(cause).isInstanceOf(RequestTimeoutException.class);
                    assertThat(scheduler.whenCancelling()).isDone();
                    assertThat(scheduler.isFinished()).isTrue();
                    assertThat(scheduler.whenCancelled()).isNotDone();
                    passed.set(true);
                }
            };
            scheduler.initAndStart(eventExecutor, task);
            assertThat(scheduler.isFinished()).isFalse();

            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            assertThat(scheduler.state()).isEqualTo(State.SCHEDULED);

            schedulerRef.set(scheduler);
            whenTimedOutRef.set(scheduler.whenCancelled());
            completed.set(true);
        });
        await().untilTrue(passed);
        await().untilTrue(completed);
        whenTimedOutRef.get().join();
        assertThat(schedulerRef.get().isFinished()).isTrue();
    }

    @Test
    void whenTimingOutAndWhenTimedOut2() {
        final AtomicReference<CompletableFuture<Throwable>> whenTimingOutRef = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Throwable>> whenTimedOutRef = new AtomicReference<>();
        executeInEventLoop(Long.MAX_VALUE, scheduler -> {
            final CompletableFuture<Throwable> whenTimingOut = scheduler.whenCancelling();
            final CompletableFuture<Throwable> whenTimedOut = scheduler.whenCancelled();
            assertThat(whenTimingOut).isNotDone();
            assertThat(whenTimedOut).isNotDone();
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            whenTimingOutRef.set(whenTimingOut);
            whenTimedOutRef.set(whenTimedOut);
        });
        whenTimingOutRef.get().join();
        whenTimedOutRef.get().join();
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void whenCancellingAndWhenCancelled(boolean server) {
        final AtomicReference<CancellationScheduler> schedulerRef = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Throwable>> whenCancellingRef = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Throwable>> whenCancelledRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean passed = new AtomicBoolean();

        final Class<? extends TimeoutException> cancellationExceptionType;
        if (server) {
            cancellationExceptionType = RequestTimeoutException.class;
        } else {
            cancellationExceptionType = ResponseTimeoutException.class;
        }

        eventExecutor.execute(() -> {
            final DefaultCancellationScheduler scheduler =
                    new DefaultCancellationScheduler(Long.MAX_VALUE, server);
            final CancellationTask task = new CancellationTask() {
                @Override
                public boolean canSchedule() {
                    return true;
                }

                @Override
                public void run(Throwable cause) {
                    assertThat(cause).isInstanceOf(cancellationExceptionType);
                    assertThat(scheduler.whenCancelling()).isDone();
                    assertThat(scheduler.isFinished()).isTrue();
                    assertThat(scheduler.whenCancelled()).isNotDone();
                    passed.set(true);
                }
            };
            scheduler.initAndStart(eventExecutor, task);
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

        assertThat(whenCancellingRef.get().join()).isInstanceOf(cancellationExceptionType);
        assertThat(whenCancelledRef.get().join()).isInstanceOf(cancellationExceptionType);
        assertThat(schedulerRef.get().isFinished()).isTrue();
    }

    @Test
    void pendingTimeout() {
        final CancellationScheduler scheduler = new DefaultCancellationScheduler(1000);
        scheduler.setTimeoutNanos(EXTEND, 1000);
        assertThat(scheduler.timeoutNanos()).isEqualTo(2000);
        scheduler.setTimeoutNanos(SET_FROM_NOW, 1000);
        assertThat(scheduler.timeoutNanos()).isEqualTo(1000);

        scheduler.cancelScheduled();
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
            CancellationScheduler scheduler = new DefaultCancellationScheduler(MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(1000));
            scheduler.initAndStart(eventExecutor, noopTask);
            assertThat(scheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(2000));

            scheduler = new DefaultCancellationScheduler(MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(2000));
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.initAndStart(eventExecutor, noopTask);
            assertTimeoutWithTolerance(scheduler.timeoutNanos(), MILLISECONDS.toNanos(1000));

            scheduler = new DefaultCancellationScheduler(MILLISECONDS.toNanos(1000));
            scheduler.cancelScheduled();
            scheduler.initAndStart(eventExecutor, noopTask);
            assertThat(scheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(1000));

            scheduler = new DefaultCancellationScheduler(MILLISECONDS.toNanos(1000));
            scheduler.clearTimeout();
            scheduler.initAndStart(eventExecutor, noopTask);
            assertThat(scheduler.timeoutNanos()).isZero();

            scheduler = new DefaultCancellationScheduler(MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(EXTEND, MILLISECONDS.toNanos(2000));
            scheduler.setTimeoutNanos(SET_FROM_NOW, MILLISECONDS.toNanos(1000));
            scheduler.setTimeoutNanos(SET_FROM_START, MILLISECONDS.toNanos(10000));
            scheduler.initAndStart(eventExecutor, noopTask);
            assertTimeoutWithTolerance(scheduler.timeoutNanos(), MILLISECONDS.toNanos(10000));
            completed.set(true);
        });
        await().untilTrue(completed);
    }

    @Test
    void initializeOnlyOnce() {
        final AtomicBoolean completed = new AtomicBoolean();
        final CancellationScheduler scheduler = new DefaultCancellationScheduler(MILLISECONDS.toNanos(100));
        eventExecutor.execute(() -> {
            scheduler.initAndStart(eventExecutor, noopTask);
            assertThat(scheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(100));

            assertThatThrownBy(() -> scheduler.initAndStart(eventExecutor, noopTask))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Can't init() more than once");
            completed.set(true);
        });

        await().untilTrue(completed);
    }

    @Test
    void multiple_ClearTimeoutInWhenCancelling() {
        final AtomicBoolean completed = new AtomicBoolean();
        final CancellationScheduler scheduler = new DefaultCancellationScheduler(MILLISECONDS.toNanos(100));
        scheduler.whenCancelling().thenRun(() -> {
            scheduler.cancelScheduled();
            scheduler.cancelScheduled();
            completed.set(true);
        });
        eventExecutor.execute(() -> {
            scheduler.initAndStart(eventExecutor, noopTask);
            assertThat(scheduler.timeoutNanos()).isEqualTo(MILLISECONDS.toNanos(100));
        });

        await().untilTrue(completed);
    }

    @Test
    void immediateFinishTriggersCompletion() {
        final DefaultCancellationScheduler scheduler = new DefaultCancellationScheduler(Long.MAX_VALUE);
        scheduler.init(eventExecutor);

        final Throwable throwable = new Throwable();

        assertThat(scheduler.whenCancelling()).isNotCompleted();
        assertThat(scheduler.state()).isEqualTo(State.INIT);

        scheduler.finishNow(throwable);

        await().untilAsserted(() -> assertThat(scheduler.state()).isEqualTo(State.FINISHED));
        assertThat(scheduler.whenCancelling()).isCompleted();
        assertThat(scheduler.whenCancelled()).isCompleted();
        assertThat(scheduler.cause()).isSameAs(throwable);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void immediateFinishWithoutCause(boolean server) {
        final DefaultCancellationScheduler scheduler = new DefaultCancellationScheduler(Long.MAX_VALUE, server);

        scheduler.init(eventExecutor);

        assertThat(scheduler.whenCancelling()).isNotCompleted();
        assertThat(scheduler.state()).isEqualTo(State.INIT);

        scheduler.finishNow();

        await().untilAsserted(() -> assertThat(scheduler.state()).isEqualTo(State.FINISHED));
        assertThat(scheduler.whenCancelling()).isCompleted();
        assertThat(scheduler.whenCancelled()).isCompleted();
        if (server) {
            assertThat(scheduler.cause()).isInstanceOf(RequestTimeoutException.class);
        } else {
            assertThat(scheduler.cause()).isInstanceOf(ResponseTimeoutException.class);
        }
    }

    @Test
    void immediateCancellation() {
        // Tests that there is no need to go through the event loop for task invocation
        final DefaultCancellationScheduler scheduler = new DefaultCancellationScheduler(Long.MAX_VALUE);
        scheduler.init(eventExecutor);
        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        scheduler.updateTask(throwableRef::set);

        final Throwable throwable = new Throwable();
        scheduler.finishNow(throwable);
        assertThat(scheduler.cause()).isSameAs(throwable);
        assertThat(throwableRef.get()).isSameAs(throwable);
    }

    @Test
    void concurrentUpdateTask_onlyOneExecutedIfNotFinished() throws Exception {
        final DefaultCancellationScheduler scheduler = new DefaultCancellationScheduler(Long.MAX_VALUE);
        scheduler.init(eventExecutor);
        scheduler.start();
        final int numTasks = 10;
        final AtomicInteger atomicInteger = new AtomicInteger();
        final ExecutorService executor = Executors.newFixedThreadPool(numTasks);
        final CountDownLatch waitLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(numTasks);
        try {
            for (int i = 0; i < numTasks; i++) {
                executor.execute(() -> {
                    try {
                        waitLatch.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    scheduler.updateTask(cause -> atomicInteger.incrementAndGet());
                    doneLatch.countDown();
                });
            }
        } finally {
            executor.shutdown();
        }
        waitLatch.countDown();
        doneLatch.await();

        scheduler.finishNow();
        assertThat(atomicInteger.get()).isEqualTo(1);
    }

    @Test
    void concurrentUpdateTask_allExecutedIfFinished() throws Exception {
        final DefaultCancellationScheduler scheduler = new DefaultCancellationScheduler(Long.MAX_VALUE);
        scheduler.init(eventExecutor);
        scheduler.start();
        final int numTasks = 10;
        final AtomicInteger atomicInteger = new AtomicInteger();
        final ExecutorService executor = Executors.newFixedThreadPool(numTasks);
        final CountDownLatch waitLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(numTasks);
        try {
            for (int i = 0; i < numTasks; i++) {
                executor.execute(() -> {
                    try {
                        waitLatch.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    scheduler.updateTask(cause -> atomicInteger.incrementAndGet());
                    doneLatch.countDown();
                });
            }
        } finally {
            executor.shutdown();
        }
        scheduler.finishNow();
        waitLatch.countDown();
        doneLatch.await();

        assertThat(atomicInteger.get()).isEqualTo(numTasks);
    }

    @Test
    void timeoutNanos_fromNow() throws Exception {
        final AtomicLong ticker = new AtomicLong();
        final DefaultCancellationScheduler scheduler =
                new DefaultCancellationScheduler(1000, false, ticker::get);
        scheduler.init(eventExecutor);
        assertThat(scheduler.timeoutNanos()).isEqualTo(1000);

        scheduler.setTimeoutNanos(SET_FROM_NOW, 5000);
        assertThat(scheduler.timeoutNanos()).isEqualTo(5000);

        ticker.addAndGet(2000);

        scheduler.start();

        // 5000 (set from now) - 2000 (elapsed time until start)
        assertThat(scheduler.timeoutNanos()).isEqualTo(3000);

        ticker.addAndGet(1000);
        scheduler.setTimeoutNanos(SET_FROM_NOW, 5000);
        // 1000 (since start time) + 5000 (set from now)
        assertThat(scheduler.timeoutNanos()).isEqualTo(6000);

        scheduler.setTimeoutNanos(EXTEND, 1000);
        // 6000 (previous timeout) + 1000 (extend)
        assertThat(scheduler.timeoutNanos()).isEqualTo(7000);

        scheduler.clearTimeout();
    }

    @Test
    void extendNanos_immediateExecution() throws Exception {
        final AtomicLong ticker = new AtomicLong();
        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        final DefaultCancellationScheduler scheduler =
                new DefaultCancellationScheduler(1000, false, ticker::get);
        scheduler.init(eventExecutor);
        scheduler.updateTask(throwableRef::set);
        scheduler.setTimeoutNanos(EXTEND, -2000);
        assertThat(scheduler.timeoutNanos()).isEqualTo(-1000);

        // because the scheduler didn't start yet
        assertThat(throwableRef).hasNullValue();

        scheduler.setTimeoutNanos(EXTEND, 2000);
        assertThat(scheduler.timeoutNanos()).isEqualTo(1000);
        scheduler.start();
        scheduler.setTimeoutNanos(EXTEND, -2000);
        assertThat(throwableRef).hasValueMatching(t -> t instanceof ResponseTimeoutException);

        scheduler.clearTimeout();
    }

    @Test
    void zero_notInfinite() throws Exception {
        final AtomicLong ticker = new AtomicLong();
        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        final DefaultCancellationScheduler scheduler =
                new DefaultCancellationScheduler(1000, false, ticker::get);
        scheduler.init(eventExecutor);
        scheduler.updateTask(throwableRef::set);

        assertThat(scheduler.timeoutNanos()).isEqualTo(1000);
        scheduler.setTimeoutNanos(EXTEND, -1000);
        assertThat(scheduler.timeoutNanos()).isEqualTo(0);

        scheduler.finishNow();
        assertThat(throwableRef).doesNotHaveNullValue();
    }

    static void assertTimeoutWithTolerance(long actualNanos, long expectedNanos) {
        assertTimeoutWithTolerance(actualNanos, expectedNanos, MILLISECONDS.toNanos(200));
    }

    static void assertTimeoutWithTolerance(long actualNanos, long expectedNanos, long toleranceNanos) {
        assertThat(actualNanos).isCloseTo(expectedNanos, Offset.offset(toleranceNanos));
    }
}

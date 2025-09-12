/*
 * Copyright 2025 LY Corporation
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
package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.AssertionsForClassTypes.within;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.channel.DefaultEventLoop;

class RetrySchedulerTest {
    private static final class MockRetryTask implements Runnable {
        private final Consumer<MockRetryTask> runnable;
        private final int retryTaskNumber;
        private final AtomicBoolean executed;
        private final AtomicLong scheduledTimeNanos;
        private final AtomicLong executionTimeNanos;

        MockRetryTask(Consumer<MockRetryTask> runnable, int retryTaskNumber) {
            this.runnable = runnable;
            this.retryTaskNumber = retryTaskNumber;
            executed = new AtomicBoolean(false);
            scheduledTimeNanos = new AtomicLong(System.nanoTime());
            executionTimeNanos = new AtomicLong();
        }

        @Override
        public void run() {
            assertThat(executed.get()).isFalse();
            executed.set(true);
            executionTimeNanos.set(System.nanoTime());
            runnable.accept(this);
        }

        void setScheduledTimeNanos() {
            scheduledTimeNanos.set(System.nanoTime());
        }

        long scheduledTimeNanos() {
            return scheduledTimeNanos.get();
        }

        long executionTimeNanos() {
            assertThat(executed.get()).isTrue();
            return executionTimeNanos.get();
        }

        int nextRetryTaskNumber() {
            return retryTaskNumber;
        }
    }

    private static final class ExceptionCatchingEventLoop extends DefaultEventLoop {
        private final List<Throwable> exceptionsCaughtOnEventLoop = Collections.synchronizedList(
                new ArrayList<>());

        @Override
        protected void run() {
            try {
                super.run();
            } catch (Throwable t) {
                exceptionsCaughtOnEventLoop.add(t);
                throw t;
            }
        }

        List<Throwable> exceptionsCaughtOnEventLoop() {
            return exceptionsCaughtOnEventLoop;
        }
    }

    private final AtomicInteger nextRetryTaskNumber = new AtomicInteger(0);
    private final List<MockRetryTask> executedRetryTasks = Collections.synchronizedList(new ArrayList<>());
    private static final long RETRY_TASK_EXECUTION_TIME_TOLERANCE = 50L;
    private ExceptionCatchingEventLoop retryEventLoop;

    @BeforeEach
    void setUp() {
        retryEventLoop = new ExceptionCatchingEventLoop();
    }

    @AfterEach
    void tearDown() throws ExecutionException, InterruptedException {
        nextRetryTaskNumber.set(0);
        executedRetryTasks.clear();
        retryEventLoop.shutdownGracefully(0, 3, TimeUnit.SECONDS).get();
        retryEventLoop = null;
    }

    @Test
    void schedule_noDelay_executeImmediately() {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        );

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 0)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();

            assertRetryTaskExecutionsAt(0);

            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void schedule_withDelay_executeAfterDelay() {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(retryEventLoop,
                                                                          System.nanoTime() +
                                                                          TimeUnit.SECONDS.toNanos(5)
        );

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 200L)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        await().untilAsserted(
                () -> assertRetryTaskExecutionsAt(200L)
        );

        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        assertNoExceptionsOnRetryEventLoop();
    }

    // We only support sequential scheduling at the moment.
    @Test
    void schedule_whileScheduling_throwsIllegalStateException() throws InterruptedException {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        );

        final AtomicLong schedulingTimeNanos = new AtomicLong();

        runOnRetryEventLoop(() -> {
            schedulingTimeNanos.set(System.nanoTime());
            assertThat(scheduler.trySchedule(nextRetryTask(), 1_000)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        Thread.sleep(200);

        runOnRetryEventLoop(() -> {
            assertThatThrownBy(() -> scheduler.trySchedule(nextRetryTask(), 0))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(scheduler.whenClosed()).isNotDone();
            assertThatThrownBy(() -> scheduler.trySchedule(nextRetryTask(), 100))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(scheduler.whenClosed()).isNotDone();
            assertThatThrownBy(() -> scheduler.trySchedule(nextRetryTask(), 1_000))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        Thread.sleep(1_000 + RETRY_TASK_EXECUTION_TIME_TOLERANCE + 100);

        assertRetryTaskExecutionsAt(1_000);

        assertNoExceptionsOnRetryEventLoop();
    }

    @RepeatedTest(5)
    void schedule_multiple_executeMultipleAfterDelay() throws InterruptedException {
        // then retry task each after 100ms
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        );

        final CountDownLatch retryDone = new CountDownLatch(1);
        final AtomicLong schedulingTimeNanos = new AtomicLong();

        final int numberOfAttempts = 10;
        final List<Long> expectedDelaysMillis = Collections.synchronizedList(new ArrayList<>());
        final List<MockRetryTask> tasks = Collections.synchronizedList(new ArrayList<>());

        final Random random = new Random();

        for (int i = 0; i < numberOfAttempts; i++) {
            if (random.nextBoolean()) {
                expectedDelaysMillis.add(0L);
            } else {
                expectedDelaysMillis.add((long) random.nextInt(300));
            }
        }

        for (int i = 0; i < numberOfAttempts - 1; i++) {
            final int attemptIndex = i;
            tasks.add(nextRetryTask(() -> {
                final MockRetryTask nextRetryTask = tasks.get(attemptIndex + 1);
                final long nextDelayMillis = expectedDelaysMillis.get(attemptIndex + 1);
                final long nextDelayMillisForCall;

                if (random.nextInt(3) < 2) {
                    // 0, 1
                    final long diffNextDelayAndMinimumBackoff = random.nextInt(50);

                    if (random.nextBoolean()) {
                        scheduler.applyMinimumBackoffMillisForNextRetry(
                                nextDelayMillis - diffNextDelayAndMinimumBackoff);
                        nextDelayMillisForCall = nextDelayMillis;
                    } else {
                        scheduler.applyMinimumBackoffMillisForNextRetry(
                                nextDelayMillis);
                        nextDelayMillisForCall = nextDelayMillis - diffNextDelayAndMinimumBackoff;
                    }
                } else {
                    // 2
                    nextDelayMillisForCall = nextDelayMillis;
                }

                nextRetryTask.setScheduledTimeNanos();
                // In the end we expect to execute that retry task after nextDelayMillis.
                assertThat(scheduler.trySchedule(nextRetryTask, nextDelayMillisForCall)).isTrue();
            }));
        }
        tasks.add(nextRetryTask(retryDone::countDown));

        runOnRetryEventLoop(() -> {
            schedulingTimeNanos.set(System.nanoTime());
            tasks.get(0).setScheduledTimeNanos();
            assertThat(scheduler.trySchedule(tasks.get(0), expectedDelaysMillis.get(0))).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        await().until(() -> retryDone.getCount() == 0);

        await().untilAsserted(
                () -> {
                    assertRetryTaskExecutionsAt(expectedDelaysMillis);
                });

        Thread.sleep(RETRY_TASK_EXECUTION_TIME_TOLERANCE + 1_000);
        assertRetryTaskExecutionsAt(expectedDelaysMillis);
        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void schedule_withDelayAndMinimumBackoff_executeAfterDelay() {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        );

        final long minimumBackoffMillis = 200L;
        final long delayMillis = minimumBackoffMillis +
                                 RETRY_TASK_EXECUTION_TIME_TOLERANCE +
                                 RETRY_TASK_EXECUTION_TIME_TOLERANCE +
                                 200L;

        runOnRetryEventLoop(() -> {
            scheduler.applyMinimumBackoffMillisForNextRetry(Long.MIN_VALUE);
            scheduler.applyMinimumBackoffMillisForNextRetry(-1);
            scheduler.applyMinimumBackoffMillisForNextRetry(0);
            scheduler.applyMinimumBackoffMillisForNextRetry(minimumBackoffMillis - 200);
            // Should override all the previous calls.
            scheduler.applyMinimumBackoffMillisForNextRetry(minimumBackoffMillis);
            assertThat(scheduler.trySchedule(nextRetryTask(), delayMillis)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        await().untilAsserted(
                () -> assertRetryTaskExecutionsAt(delayMillis)
        );

        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void schedule_withDelayAndMinimumBackoff_executeAfterMinimumBackoff() {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        );

        final long delayMillis = 200L;
        final long minimumBackoffMillis = delayMillis +
                                          RETRY_TASK_EXECUTION_TIME_TOLERANCE +
                                          RETRY_TASK_EXECUTION_TIME_TOLERANCE +
                                          200L;

        runOnRetryEventLoop(() -> {
            scheduler.applyMinimumBackoffMillisForNextRetry(minimumBackoffMillis);
            assertThat(scheduler.trySchedule(nextRetryTask(), delayMillis)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        await().untilAsserted(
                () -> assertRetryTaskExecutionsAt(minimumBackoffMillis)
        );

        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void schedule_beyondDeadline_returnFalse() throws InterruptedException {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(1_000)
        );

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 1_001)).isFalse();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        Thread.sleep(1_001 + 100);

        assertNoRetryTaskExecutions();

        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void schedule_exceptionInRetryTask_closeExceptionally() {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(1)
        );

        final AtomicReference<CompletableFuture<Void>> whenClosedRef = new AtomicReference<>();

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(
                    nextRetryTask(() -> {
                        throw new AnticipatedException("bad task");
                    }),
                    200L)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
            whenClosedRef.set(scheduler.whenClosed());
        });

        await().untilAsserted(() -> {
            try {
                assertThat(whenClosedRef.get()).isCompletedExceptionally();
                whenClosedRef.get().get();
                fail();
            } catch (Throwable e) {
                assertThat(e.getCause()).isInstanceOf(AnticipatedException.class)
                                        .hasMessage("bad task");
            }
        });

        assertRetryTaskExecutionsAt(200L);

        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void schedule_closeRetryEventLoop_closeExceptionally() throws Exception {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        );

        final AtomicReference<CompletableFuture<Void>> whenClosedRef = new AtomicReference<>();

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 1_000)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
            whenClosedRef.set(scheduler.whenClosed());
        });

        // Close the event loop before the task is executed.
        retryEventLoop.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS).get();

        assertThat(whenClosedRef.get()).isCompletedExceptionally();
        try {
            whenClosedRef.get().get();
            fail();
        } catch (Throwable e) {
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining(ClientFactory.class.getSimpleName())
                                    .hasMessageContaining("has been closed");
        }

        Thread.sleep(1_000 + RETRY_TASK_EXECUTION_TIME_TOLERANCE + 100);

        assertNoRetryTaskExecutions();
        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void applyMinimumBackoff_whileScheduling_throwIllegalStateException() throws InterruptedException {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        );

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 1_000)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        Thread.sleep(200);

        runOnRetryEventLoop(() -> {
            assertThatThrownBy(() -> scheduler.applyMinimumBackoffMillisForNextRetry(42))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        Thread.sleep(800 + RETRY_TASK_EXECUTION_TIME_TOLERANCE + 100);

        assertRetryTaskExecutionsAt(1_000L);

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.whenClosed()).isNotDone();
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void applyMinimumBackoff_thatExceedsDeadline_rejectEverySchedule() throws InterruptedException {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(1)
        );

        runOnRetryEventLoop(() -> {
            scheduler.applyMinimumBackoffMillisForNextRetry(1_001);
            assertThat(scheduler.whenClosed()).isNotDone();
            assertThat(scheduler.trySchedule(nextRetryTask(), 0)).isFalse();
            assertThat(scheduler.trySchedule(nextRetryTask(), 100)).isFalse();
        });

        Thread.sleep(100 + RETRY_TASK_EXECUTION_TIME_TOLERANCE + 100);

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.whenClosed()).isNotDone();
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        assertNoRetryTaskExecutions();
        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void close_thenSchedule_returnFalse() throws InterruptedException {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        );

        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
            assertThat(scheduler.trySchedule(nextRetryTask(), 200)).isFalse();
        });

        Thread.sleep(200 + RETRY_TASK_EXECUTION_TIME_TOLERANCE + 100);

        assertNoRetryTaskExecutions();
        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void close_whileSchedule_cancelRetryTask() throws InterruptedException {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        );

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 1000L)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        Thread.sleep(250);
        assertNoRetryTaskExecutions();

        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        Thread.sleep(750 + RETRY_TASK_EXECUTION_TIME_TOLERANCE + 100);

        assertNoRetryTaskExecutions();
        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void close_thenDoSomething_doesNotScheduleAndThrow() throws InterruptedException {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        );

        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 0)).isFalse();
            assertThat(scheduler.trySchedule(nextRetryTask(), 200)).isFalse();
        });

        Thread.sleep(200 + RETRY_TASK_EXECUTION_TIME_TOLERANCE + 100);

        runOnRetryEventLoop(() -> {
            scheduler.applyMinimumBackoffMillisForNextRetry(Long.MIN_VALUE);
            scheduler.close();
            scheduler.applyMinimumBackoffMillisForNextRetry(-1);
            scheduler.applyMinimumBackoffMillisForNextRetry(0);
            scheduler.applyMinimumBackoffMillisForNextRetry(100);
            scheduler.close();
        });

        assertNoRetryTaskExecutions();
        assertNoExceptionsOnRetryEventLoop();
    }

    @Test
    void schedule_applyMinimumBackoff_close_outsideRetryEventLoop_throwsIllegalStateException() {
        final DefaultRetryScheduler scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        );

        assertThatThrownBy(() -> scheduler.trySchedule(nextRetryTask(), 100)).isInstanceOf(
                IllegalStateException.class);

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        assertThatThrownBy(() -> scheduler.applyMinimumBackoffMillisForNextRetry(200))
                .isInstanceOf(IllegalStateException.class);

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        assertThatThrownBy(scheduler::close).isInstanceOf(IllegalStateException.class);

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.whenClosed()).isNotDone();
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        assertNoRetryTaskExecutions();
        assertNoExceptionsOnRetryEventLoop();
    }

    private MockRetryTask nextRetryTask() {
        return nextRetryTask(() -> {
            // Default does nothing.
        });
    }

    private MockRetryTask nextRetryTask(Runnable innerTask) {
        final int taskNumber = nextRetryTaskNumber.getAndIncrement();
        return new MockRetryTask(task -> {
            assertThat(retryEventLoop.inEventLoop()).isTrue();
            executedRetryTasks.add(task);
            innerTask.run();
        }, taskNumber);
    }

    private void runOnRetryEventLoop(Runnable runnable) {
        final CountDownLatch latch = new CountDownLatch(1);
        retryEventLoop.execute(() -> {
            try {
                runnable.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            fail(e);
        }

        assertNoExceptionsOnRetryEventLoop();
    }

    private void assertNoRetryTaskExecutions() {
        assertRetryTaskExecutionsAt();
    }

    private void assertRetryTaskExecutionsAt(long... expectedDelaysMillis) {
        assertRetryTaskExecutionsAt(
                expectedDelaysMillis == null ?
                Collections.emptyList()
                                             : LongStream.of(expectedDelaysMillis).boxed()
                                                         .collect(Collectors.toList())
        );
    }

    private void assertRetryTaskExecutionsAt(List<Long> expectedDelaysMillis) {
        assertThat(executedRetryTasks).hasSize(expectedDelaysMillis.size());

        if (expectedDelaysMillis.isEmpty()) {
            return;
        }

        for (int expectedRetryTaskNumber = 0; expectedRetryTaskNumber < expectedDelaysMillis.size();
             expectedRetryTaskNumber++) {
            final MockRetryTask task = executedRetryTasks.get(expectedRetryTaskNumber);

            assertThat(task.nextRetryTaskNumber()).isEqualTo(expectedRetryTaskNumber);

            final long actualDelayMillis = TimeUnit.NANOSECONDS.toMillis(
                    task.executionTimeNanos() - task.scheduledTimeNanos()
            );
            final long expectedDelayMillis = expectedDelaysMillis.get(expectedRetryTaskNumber);

            assertThat(actualDelayMillis)
                    .as("Retry task %d being delayed appropriately", expectedRetryTaskNumber)
                    .isCloseTo(expectedDelayMillis, within(RETRY_TASK_EXECUTION_TIME_TOLERANCE));
        }
    }

    private void assertNoExceptionsOnRetryEventLoop() {
        assertThat(retryEventLoop.exceptionsCaughtOnEventLoop())
                .as("No exceptions thrown on retry event loop")
                .isEmpty();
    }
}

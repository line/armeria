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
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.internal.testing.BlockingUtils;

import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.ScheduledFuture;

class RetrySchedulerTest {
    private static final long RETRY_SCHEDULER_SCHEDULE_ADJUSTMENT_TOLERANCE_MILLIS = 5L;
    // Number of millis we expect the retry event loop will take to actually execute a delayed task
    // *after* its delay.
    private static final long MAX_EXECUTION_DELAY_MILLIS = 100L;

    private final List<ManagedRetryTask> executedRetryTasks = Collections.synchronizedList(new ArrayList<>());
    private ManagedRetryEventLoop retryEventLoop;
    private DefaultRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        retryEventLoop = new ManagedRetryEventLoop();
        scheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        );
    }

    @AfterEach
    void tearDown() throws ExecutionException, InterruptedException {
        // If the test terminates the event loop it needs to a local copy.
        runOnRetryEventLoop(() -> {
            if (!scheduler.whenClosed().isDone()) {
                scheduler.close();
                assertThat(scheduler.whenClosed()).isCompleted();
            }
        });

        retryEventLoop.shutdownGracefully(0, 3, TimeUnit.SECONDS).get();
        assertNoExceptionsOnRetryEventLoop(retryEventLoop);

        ManagedRetryTask.nextRetryTaskNumber.set(0);
        executedRetryTasks.clear();
    }

    @Test
    void schedule_noDelay_executeImmediately() {
        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 0)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();

            assertNumRetryTaskExecutions(1);
            assertNoScheduleCalls();
        });
    }

    @Test
    void schedule_withDelay_executeAfterDelay() {
        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 200L)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        await().untilAsserted(
                () -> {
                    assertNumRetryTaskExecutions(1);
                    assertEventLoopScheduleCalls(200L);
                }
        );
    }

    // We only support sequential scheduling at the moment.
    @Test
    void schedule_whileScheduling_throwsIllegalStateException() throws InterruptedException {
        final AtomicLong schedulingTimeNanos = new AtomicLong();

        runOnRetryEventLoop(() -> {
            schedulingTimeNanos.set(System.nanoTime());
            assertThat(scheduler.trySchedule(nextRetryTask(), 1_000L)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(1_000L);

        Thread.sleep(200);

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(1_000L);

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

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(1_000L);

        await().untilAsserted(() -> {
            assertNumRetryTaskExecutions(1);
            assertEventLoopScheduleCalls(1_000L);
        });
    }

    private static Stream<Arguments> schedule_multiple_executeMultipleAfterDelay_args() {
        return Stream.of(
                Arguments.of(
                        new RetryPlan(
                                "All increasing delays",
                                Arrays.asList(
                                        new ScheduleCall(100L),
                                        new ScheduleCall(200L),
                                        new ScheduleCall(300L)
                                ),
                                Arrays.asList(100L, 200L, 300L)
                        )
                ),
                Arguments.of(
                        new RetryPlan(
                                "All decreasing delays",
                                Arrays.asList(
                                        new ScheduleCall(300L),
                                        new ScheduleCall(200L),
                                        new ScheduleCall(100L)
                                ),
                                Arrays.asList(300L, 200L, 100L)
                        )
                ),
                Arguments.of(
                        new RetryPlan(
                                "No order in delays, some direct",
                                Arrays.asList(
                                        new ScheduleCall(200L),
                                        new ScheduleCall(-10L),
                                        new ScheduleCall(0L),
                                        new ScheduleCall(100L)
                                ),
                                Arrays.asList(200L, /* direct, */ /* direct, */ 100L
                                )
                        )
                ),
                Arguments.of(
                        new RetryPlan(
                                "Many direct",
                                IntStream.range(0, 128)
                                         .mapToObj(i -> new ScheduleCall(0L))
                                         .collect(Collectors.toList()),
                                Collections.emptyList() // All direct
                        )
                ),
                Arguments.of(
                        new RetryPlan(
                                "Mixed delays and minimum backoffs",
                                Arrays.asList(
                                        new ScheduleCall(0L),
                                        new ScheduleCall(100L, Collections.singletonList(50L)),
                                        new ScheduleCall(-42L),
                                        new ScheduleCall(50L, Collections.singletonList(100L)),
                                        new ScheduleCall(0L)
                                ),
                                Arrays.asList(/* direct, */ 100L, /* direct, */ 100L/*, direct */)
                        )
                ),
                Arguments.of(
                        new RetryPlan(
                                "Multiple minimum backoffs",
                                Arrays.asList(
                                        new ScheduleCall(0L,
                                                         Arrays.asList(Long.MIN_VALUE, -50L, 100L)
                                        ),
                                        new ScheduleCall(100L,
                                                         Arrays.asList(50L, Long.MIN_VALUE)
                                        ),
                                        new ScheduleCall(-10L, Collections.singletonList(200L))
                                ),
                                Arrays.asList(
                                        100L,
                                        100L,
                                        200L
                                )
                        )
                )
        );
    }

    @ParameterizedTest
    @MethodSource("schedule_multiple_executeMultipleAfterDelay_args")
    void schedule_multiple_executeMultipleAfterDelay(RetryPlan retryPlan) {
        final int numberOfAttempts = retryPlan.retryTaskDelaysMillis.size();
        assert numberOfAttempts >= 1;

        final CountDownLatch retryDone = execRetryPlan(retryPlan);

        await().untilAsserted(
                () -> {
                    assertNumRetryTaskExecutions(numberOfAttempts);
                    assertEventLoopScheduleCalls(retryPlan.expectedScheduleDelayMillis);
                });

        assertThat(retryDone.getCount()).isZero();
    }

    @Test
    void schedule_withDelayAndMinimumBackoff_executeAfterDelay() {
        final long minimumBackoffMillis = 200L;
        final long delayMillis = minimumBackoffMillis + 200L;

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

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(delayMillis);

        await().untilAsserted(
                () -> {
                    assertNumRetryTaskExecutions(1);
                    assertEventLoopScheduleCalls(delayMillis);
                }
        );
    }

    @Test
    void schedule_withDelayAndMinimumBackoff_executeAfterMinimumBackoff() {
        final long delayMillis = 200L;
        final long minimumBackoffMillis = delayMillis + 200L;

        runOnRetryEventLoop(() -> {
            scheduler.applyMinimumBackoffMillisForNextRetry(minimumBackoffMillis);
            assertThat(scheduler.trySchedule(nextRetryTask(), delayMillis)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        await().untilAsserted(
                () -> {
                    assertNumRetryTaskExecutions(1);
                    assertEventLoopScheduleCalls(minimumBackoffMillis);
                }
        );
    }

    @Test
    void schedule_beyondDeadline_returnFalse() {
        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(
                               nextRetryTask(),
                               10_000 +
                               RETRY_SCHEDULER_SCHEDULE_ADJUSTMENT_TOLERANCE_MILLIS + 1
                       )
            ).isFalse();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        assertNumRetryTaskExecutions(0);
        assertNoScheduleCalls();
    }

    @Test
    void schedule_exceptionInRetryTask_closeExceptionally() {
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

        assertNumRetryTaskExecutions(1);
        assertEventLoopScheduleCalls(200L);
    }

    @Test
    void schedule_closeRetryEventLoop_closeExceptionally() throws Exception {
        final ManagedRetryEventLoop localRetryEventLoop = new ManagedRetryEventLoop();
        final DefaultRetryScheduler localScheduler = new DefaultRetryScheduler(
                localRetryEventLoop,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        );

        final AtomicReference<CompletableFuture<Void>> whenClosedRef = new AtomicReference<>();

        runOnRetryEventLoop(localRetryEventLoop, () -> {
            assertThat(localScheduler.trySchedule(nextRetryTask(), 1_000)).isTrue();
            assertThat(localScheduler.whenClosed()).isNotDone();
            whenClosedRef.set(localScheduler.whenClosed());
        });

        // Close the event loop before the task is executed.
        localRetryEventLoop.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS).get();

        assertThat(whenClosedRef.get()).isCompletedExceptionally();
        try {
            whenClosedRef.get().get();
            fail();
        } catch (Throwable e) {
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining(ClientFactory.class.getSimpleName())
                                    .hasMessageContaining("has been closed");
        }

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(localRetryEventLoop, Collections.singletonList(1_000L));

        Thread.sleep(1_000 + MAX_EXECUTION_DELAY_MILLIS);

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(localRetryEventLoop, Collections.singletonList(1_000L));

        assertNoExceptionsOnRetryEventLoop(localRetryEventLoop);
    }

    @Test
    void schedule_clogRetryEventLoop_closeExceptionally() throws Exception {
        final DefaultRetryScheduler localScheduler = new DefaultRetryScheduler(
                retryEventLoop,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        );

        runOnRetryEventLoop(() -> {
            assertThat(localScheduler.trySchedule(nextRetryTask(), 500L)).isTrue();
            assertThat(localScheduler.whenClosed()).isNotDone();
        });

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(500L);

        runOnRetryEventLoop(() -> {
            BlockingUtils.blockingRun(() -> {
                Thread.sleep(1_500L);
            });
        });

        await().untilAsserted(() -> {
            assertNumRetryTaskExecutions(0);
            assertEventLoopScheduleCalls(500L);
        });

        await().untilAsserted(() -> {
            runOnRetryEventLoop(() -> {
                try {
                    localScheduler.whenClosed().getNow(null);
                    fail();
                } catch (Throwable e) {
                    assertThat(e.getCause()).isInstanceOf(ResponseTimeoutException.class);
                }
            });
        });

        Thread.sleep(500 + MAX_EXECUTION_DELAY_MILLIS);

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(500L);
    }

    @Test
    void schedule_retryEventLoopRejects_closeExceptionally() throws Exception {
        retryEventLoop.setRejectSchedule(true);

        final AtomicReference<CompletableFuture<Void>> whenClosedRef = new AtomicReference<>();

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 500L)).isFalse();
            assertThat(scheduler.whenClosed()).isDone();
            whenClosedRef.set(scheduler.whenClosed());
        });

        try {
            whenClosedRef.get().get();
            fail();
        } catch (Throwable e) {
            assertThat(e.getCause()).isInstanceOf(RejectedExecutionException.class);
        }
    }

    @Test
    void schedule_scheduledFutureCompletesExceptionally_closeExceptionally() {
        final AtomicReference<CompletableFuture<Void>> whenClosedRef = new AtomicReference<>();

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 1_000)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
            whenClosedRef.set(scheduler.whenClosed());
        });

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(1_000L);

        runOnRetryEventLoop(() -> {
            retryEventLoop.setExceptionWhenTaskRun(new AnticipatedException());
        });

        await().untilAsserted(() -> {
            try {
                assertThat(whenClosedRef.get()).isCompletedExceptionally();
                whenClosedRef.get().get();
                fail();
            } catch (Throwable e) {
                assertThat(e.getCause()).isInstanceOf(AnticipatedException.class);
            }
        });

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(1_000L);
    }

    @Test
    void applyMinimumBackoff_whileScheduling_throwIllegalStateException() throws InterruptedException {
        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 1_000)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(1_000L);
        Thread.sleep(200);

        runOnRetryEventLoop(() -> {
            assertThatThrownBy(() -> scheduler.applyMinimumBackoffMillisForNextRetry(42))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(1_000L);

        await().untilAsserted(
                () -> {
                    assertNumRetryTaskExecutions(1);
                    assertEventLoopScheduleCalls(1_000L);
                }
        );

        runOnRetryEventLoop(() -> assertThat(scheduler.whenClosed()).isNotDone());
    }

    @Test
    void applyMinimumBackoff_thatExceedsDeadline_rejectEverySchedule() {
        runOnRetryEventLoop(() -> {
            scheduler.applyMinimumBackoffMillisForNextRetry(
                    10_000 +
                    RETRY_SCHEDULER_SCHEDULE_ADJUSTMENT_TOLERANCE_MILLIS + 1);
            assertThat(scheduler.whenClosed()).isNotDone();
            assertThat(scheduler.trySchedule(nextRetryTask(), 0)).isFalse();
            assertThat(scheduler.trySchedule(nextRetryTask(), 100)).isFalse();
        });

        assertNoScheduleCalls();
        assertNumRetryTaskExecutions(0);

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.whenClosed()).isNotDone();
        });
    }

    @Test
    void close_afterNothing_returnTrue() {
        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        assertNoScheduleCalls();
        assertNumRetryTaskExecutions(0);
    }

    @Test
    void close_thenSchedule_returnFalse() {
        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
            assertThat(scheduler.trySchedule(nextRetryTask(), 200)).isFalse();
        });

        assertNoScheduleCalls();
        assertNumRetryTaskExecutions(0);
    }

    @Test
    void close_whileSchedule_cancelRetryTask() throws InterruptedException {
        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 1000L)).isTrue();
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        assertNumRetryTaskExecutions(0);
        assertEventLoopScheduleCalls(1000L);

        Thread.sleep(200);

        assertEventLoopScheduleCalls(1000L);
        assertNumRetryTaskExecutions(0);

        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        assertEventLoopScheduleCalls(1000L);
        assertNumRetryTaskExecutions(0);

        Thread.sleep(800 + MAX_EXECUTION_DELAY_MILLIS);

        assertEventLoopScheduleCalls(1000L);
        assertNumRetryTaskExecutions(0);
    }

    @Test
    void close_thenDoSomething_doesNotScheduleAndThrow() throws InterruptedException {
        runOnRetryEventLoop(() -> {
            scheduler.close();
            assertThat(scheduler.whenClosed()).isCompleted();
        });

        runOnRetryEventLoop(() -> {
            assertThat(scheduler.trySchedule(nextRetryTask(), 0)).isFalse();
            assertThat(scheduler.trySchedule(nextRetryTask(), 200)).isFalse();
        });

        Thread.sleep(200 + MAX_EXECUTION_DELAY_MILLIS);

        runOnRetryEventLoop(() -> {
            scheduler.applyMinimumBackoffMillisForNextRetry(Long.MIN_VALUE);
            scheduler.close();
            scheduler.applyMinimumBackoffMillisForNextRetry(-1);
            scheduler.applyMinimumBackoffMillisForNextRetry(0);
            scheduler.applyMinimumBackoffMillisForNextRetry(100);
            scheduler.close();
        });

        assertNoScheduleCalls();
        assertNumRetryTaskExecutions(0);
    }

    @Test
    void all_outsideRetryEventLoop_throwsIllegalStateException() {
        assertThatThrownBy(() -> scheduler.trySchedule(nextRetryTask(), 100)).isInstanceOf(
                IllegalStateException.class);

        assertNoScheduleCalls();
        assertNumRetryTaskExecutions(0);
        runOnRetryEventLoop(() -> {
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        assertThatThrownBy(() -> scheduler.applyMinimumBackoffMillisForNextRetry(200))
                .isInstanceOf(IllegalStateException.class);

        assertNoScheduleCalls();
        assertNumRetryTaskExecutions(0);
        runOnRetryEventLoop(() -> {
            assertThat(scheduler.whenClosed()).isNotDone();
        });

        assertThatThrownBy(scheduler::close).isInstanceOf(IllegalStateException.class);

        assertNoScheduleCalls();
        assertNumRetryTaskExecutions(0);
        runOnRetryEventLoop(() -> {
            assertThat(scheduler.whenClosed()).isNotDone();
        });
    }

    private ManagedRetryTask nextRetryTask() {
        return nextRetryTask(() -> {
            // Default does nothing.
        });
    }

    private ManagedRetryTask nextRetryTask(Runnable innerTask) {
        return new ManagedRetryTask(task -> {
            assertThat(retryEventLoop.inEventLoop()).isTrue();
            executedRetryTasks.add(task);
            innerTask.run();
        });
    }

    private void runOnRetryEventLoop(Runnable runnable) {
        runOnRetryEventLoop(retryEventLoop, runnable);
    }

    private void runOnRetryEventLoop(ManagedRetryEventLoop retryEventLoop, Runnable runnable) {
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

        assertNoExceptionsOnRetryEventLoop(retryEventLoop);
    }

    private static final class RetryPlan {
        final String name;
        final List<ScheduleCall> retryTaskDelaysMillis;
        // Does not include direct invocations as they don't call the retry event loop.
        final List<Long> expectedScheduleDelayMillis;

        RetryPlan(String name, List<ScheduleCall> retryTaskDelaysMillis,
                  List<Long> expectedScheduleDelayMillis) {
            this.name = name;
            this.retryTaskDelaysMillis = ImmutableList.copyOf(retryTaskDelaysMillis);
            this.expectedScheduleDelayMillis = ImmutableList.copyOf(expectedScheduleDelayMillis);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class ScheduleCall {
        final long delayMillis;
        final List<Long> minimumBackoffMillis;

        ScheduleCall(long delayMillis, List<Long> minimumBackoffMillis) {
            this.delayMillis = delayMillis;
            this.minimumBackoffMillis = ImmutableList.copyOf(minimumBackoffMillis);
        }

        ScheduleCall(long delayMillis) {
            this.delayMillis = delayMillis;
            minimumBackoffMillis = ImmutableList.of();
        }
    }

    @NotNull
    private CountDownLatch execRetryPlan(RetryPlan retryPlan) {
        final int numberOfAttempts = retryPlan.retryTaskDelaysMillis.size();
        final List<ManagedRetryTask> tasks = Collections.synchronizedList(new ArrayList<>());

        final Consumer<Integer> scheduleTask = retryNumber -> {
            assert 0 <= retryNumber && retryNumber < numberOfAttempts;
            assertThat(scheduler.whenClosed()).isNotDone();

            final ScheduleCall scheduleCall = retryPlan.retryTaskDelaysMillis.get(retryNumber);

            for (long minimumBackoffMillis : scheduleCall.minimumBackoffMillis) {
                scheduler.applyMinimumBackoffMillisForNextRetry(minimumBackoffMillis);
            }

            // In the end we expect to execute that retry task after scheduledDelayMillis.
            assertThat(scheduler.trySchedule(
                    tasks.get(retryNumber),
                    scheduleCall.delayMillis)
            )
                    .as("scheduling retry %d", retryNumber)
                    .isTrue();
        };

        final CountDownLatch retryDone = new CountDownLatch(1);

        for (int retryNumber = 0; retryNumber < numberOfAttempts - 1; retryNumber++) {
            final int nextRetryNumber = retryNumber + 1;
            tasks.add(nextRetryTask(() -> scheduleTask.accept(nextRetryNumber)));
        }
        tasks.add(nextRetryTask(retryDone::countDown));
        assert tasks.size() == numberOfAttempts;

        // Execute the first task.
        runOnRetryEventLoop(() -> scheduleTask.accept(0));

        return retryDone;
    }

    private void assertNoScheduleCalls() {
        assertEventLoopScheduleCalls(Collections.emptyList());
    }

    private void assertEventLoopScheduleCalls(long... expectedScheduleDelaysMillisOnEventLoop) {
        assert expectedScheduleDelaysMillisOnEventLoop != null;
        assertEventLoopScheduleCalls(LongStream.of(expectedScheduleDelaysMillisOnEventLoop)
                                               .boxed()
                                               .collect(Collectors.toList()));
    }

    private void assertEventLoopScheduleCalls(List<Long> expectedScheduleDelaysMillisOnEventLoop) {
        assertEventLoopScheduleCalls(retryEventLoop, expectedScheduleDelaysMillisOnEventLoop);
    }

    private void assertEventLoopScheduleCalls(ManagedRetryEventLoop retryEventLoop,
                                              List<Long> expectedScheduleDelaysMillisOnEventLoop) {
        assertThat(retryEventLoop.scheduleDelaysMillis())
                .as("Expected number of schedule() calls")
                .hasSize(expectedScheduleDelaysMillisOnEventLoop.size());

        for (int i = 0; i < expectedScheduleDelaysMillisOnEventLoop.size(); i++) {
            final long expectedDelayMillis = expectedScheduleDelaysMillisOnEventLoop.get(i);

            assertThat(retryEventLoop.scheduleDelaysMillis().get(i))
                    .isBetween(
                            expectedDelayMillis - RETRY_SCHEDULER_SCHEDULE_ADJUSTMENT_TOLERANCE_MILLIS,
                            expectedDelayMillis);
        }
    }

    private void assertNumRetryTaskExecutions(int numRetryTasks) {
        assertThat(executedRetryTasks).hasSize(numRetryTasks);

        for (int i = 0; i < numRetryTasks; i++) {
            final ManagedRetryTask task = executedRetryTasks.get(i);
            assertThat(task.nextRetryTaskNumber()).isEqualTo(i);
        }
    }

    private void assertNoExceptionsOnRetryEventLoop(ManagedRetryEventLoop retryEventLoop) {
        assertThat(retryEventLoop.exceptionsCaughtOnEventLoop())
                .as("No exceptions thrown on retry event loop")
                .isEmpty();
    }

    private static final class ManagedRetryTask implements Runnable {
        static final AtomicInteger nextRetryTaskNumber = new AtomicInteger(0);

        private final Consumer<ManagedRetryTask> runnable;
        private final int retryTaskNumber;
        private final AtomicBoolean executed;

        ManagedRetryTask(Consumer<ManagedRetryTask> runnable) {
            this.runnable = runnable;
            retryTaskNumber = nextRetryTaskNumber.getAndIncrement();
            executed = new AtomicBoolean(false);
        }

        @Override
        public void run() {
            assertThat(executed.get()).isFalse();
            executed.set(true);
            runnable.accept(this);
        }

        int nextRetryTaskNumber() {
            return retryTaskNumber;
        }
    }

    private static final class ManagedRetryEventLoop extends DefaultEventLoop {
        private final List<Throwable> exceptionsCaughtOnEventLoop = Collections.synchronizedList(
                new ArrayList<>());
        private final List<Long> scheduleDelaysMillis = Collections.synchronizedList(
                new ArrayList<>());
        private final AtomicBoolean rejectSchedule = new AtomicBoolean(false);
        private final AtomicReference<@Nullable RuntimeException> throwWhenTaskRun = new AtomicReference<>(
                null);

        @Override
        protected void run() {
            try {
                super.run();
            } catch (Throwable t) {
                exceptionsCaughtOnEventLoop.add(t);
                throw t;
            }
        }

        void setRejectSchedule(boolean rejectSchedule) {
            this.rejectSchedule.set(rejectSchedule);
        }

        void setExceptionWhenTaskRun(@Nullable RuntimeException t) {
            throwWhenTaskRun.set(t);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            scheduleDelaysMillis.add(TimeUnit.MILLISECONDS.convert(delay, unit));
            if (rejectSchedule.get()) {
                reject();
            }

            return super.schedule(() -> {
                final RuntimeException t = throwWhenTaskRun.get();
                if (t != null) {
                    throw t;
                }
                command.run();
            }, delay, unit);
        }

        List<Throwable> exceptionsCaughtOnEventLoop() {
            return exceptionsCaughtOnEventLoop;
        }

        List<Long> scheduleDelaysMillis() {
            return scheduleDelaysMillis;
        }
    }
}

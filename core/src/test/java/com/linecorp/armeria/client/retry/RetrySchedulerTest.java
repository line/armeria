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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.retry.RetrySchedulingException.Type;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

class RetrySchedulerTest {
    private static final long SCHEDULING_TOLERANCE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long SCHEDULING_TOLERANCE_MILLIS = TimeUnit.NANOSECONDS.toMillis(
            SCHEDULING_TOLERANCE_NANOS);

    private ReentrantLock lock;
    private Consumer<ReentrantLock> dummyRetryTask;
    private Consumer<ReentrantLock> dummyThrowingRetryTask;

    private EventLoop eventLoop;

    private RetryScheduler scheduler;

    private static class SpyableRetryTask implements Consumer<ReentrantLock> {
        private final Consumer<ReentrantLock> delegate;

        SpyableRetryTask(Consumer<ReentrantLock> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void accept(ReentrantLock retryLock) {
            delegate.accept(retryLock);
        }
    }

    @BeforeEach
    void setUp() {
        eventLoop = spy(new DefaultEventLoop());
        lock = new ReentrantShortLock();
        dummyRetryTask = new SpyableRetryTask(retryLock -> {
            assertThat(retryLock).isEqualTo(lock);
            assertThat(retryLock.isHeldByCurrentThread()).isTrue();
            assertThat(retryLock.getHoldCount()).isOne();
            retryLock.unlock();
        });

        dummyThrowingRetryTask = new SpyableRetryTask(retryLock -> {
            assertThat(retryLock).isEqualTo(lock);
            assertThat(retryLock.isHeldByCurrentThread()).isTrue();
            assertThat(retryLock.getHoldCount()).isOne();
            retryLock.unlock();
            throw new AnticipatedException();
        });
        scheduler = new RetryScheduler(lock, eventLoop);
    }

    @AfterEach
    void tearDown() throws Exception {
        assertThat(lock.isLocked()).isFalse();
        assertThat(scheduler.shutdown()).isTrue();
        eventLoop.shutdownGracefully();
    }

    @ParameterizedTest
    @MethodSource("scheduleTaskParameters")
    void testScheduleTask(int taskDelayMs, int minDelayMs, int prevDelayMs, int expectedDelayMs)
            throws Exception {
        // Convert all delays to nanos
        final long now = System.nanoTime();
        final long taskScheduledTimeNanos = now + TimeUnit.MILLISECONDS.toNanos(taskDelayMs);
        final long newEarliestNextRetryTimeNanos =
                minDelayMs >= 0 ? now + TimeUnit.MILLISECONDS.toNanos(minDelayMs) : Long.MIN_VALUE;
        final long previousEarliestNextRetryTimeNanos =
                prevDelayMs >= 0 ? now + TimeUnit.MILLISECONDS.toNanos(prevDelayMs) : Long.MIN_VALUE;

        final long expectedScheduledTimeNanos = now + TimeUnit.MILLISECONDS.toNanos(expectedDelayMs);

        // Set the previous delay if it exists
        if (prevDelayMs >= 0) {
            scheduler.addEarliestNextRetryTimeNanos(previousEarliestNextRetryTimeNanos);
        }

        // Schedule the task
        final Consumer<ReentrantLock> task = spy(dummyRetryTask);
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        scheduler.schedule(task, taskScheduledTimeNanos, newEarliestNextRetryTimeNanos, exceptionHandler);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(now, expectedScheduledTimeNanos)
        );

        Thread.sleep(expectedDelayMs + SCHEDULING_TOLERANCE_MILLIS);

        verify(task, times(1)).accept(lock);
        verifyNoMoreInteractions(exceptionHandler);
        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(now, expectedScheduledTimeNanos)
        );
    }

    private static Stream<Arguments> scheduleTaskParameters() {
        return Stream.of(
                // taskDelay, minDelay, prevDelay, expectedDelay

                // No previous delay, no minimum delay
                Arguments.of(0, -1, -1, 0),
                Arguments.of(200, -1, -1, 200),

                Arguments.of(100, 100, -1, 100),
                Arguments.of(150, 100, -1, 150),

                // With previous delay, no minimum delay
                Arguments.of(50, -1, 100, 100),
                Arguments.of(100, -1, 100, 100),
                Arguments.of(150, -1, 100, 150),

                // With both previous and minimum delay
                Arguments.of(100, 100, 200, 200),
                Arguments.of(150, 100, 200, 200),
                Arguments.of(200, 100, 200, 200),
                Arguments.of(250, 100, 200, 250)
        );
    }

    @Test
    void testScheduleTaskInThePast() throws Exception {
        final Consumer<ReentrantLock> task = spy(dummyRetryTask);
        final long taskSchedulingTime = System.nanoTime();
        final long expectedTaskRunTime = taskSchedulingTime;
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        scheduler.schedule(task, expectedTaskRunTime - 1_000_000, Long.MIN_VALUE, exceptionHandler);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskSchedulingTime, expectedTaskRunTime)
        );

        Thread.sleep(SCHEDULING_TOLERANCE_MILLIS);

        verify(task, times(1)).accept(lock);
        verifyNoMoreInteractions(exceptionHandler);
        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskSchedulingTime, expectedTaskRunTime)
        );

        final Consumer<ReentrantLock> task2 = spy(dummyRetryTask);
        final long task2SchedulingTime = System.nanoTime();
        final long expectedTask2RunTime = task2SchedulingTime;
        final Consumer<Throwable> task2ExceptionHandler = mock(Consumer.class);

        scheduler.schedule(task2, Long.MIN_VALUE, Long.MIN_VALUE, task2ExceptionHandler);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskSchedulingTime, expectedTaskRunTime),
                EventLoopScheduleCall.of(task2SchedulingTime, expectedTask2RunTime)
        );

        Thread.sleep(SCHEDULING_TOLERANCE_MILLIS);

        verify(task2, times(1)).accept(lock);
        verifyNoMoreInteractions(task2ExceptionHandler);
        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskSchedulingTime, expectedTaskRunTime),
                EventLoopScheduleCall.of(task2SchedulingTime, expectedTask2RunTime)
        );
    }

    @Test
    void testFailingScheduleWithInvalidArguments() {
        final Consumer<ReentrantLock> task = spy(dummyRetryTask);
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        assertThatThrownBy(() -> scheduler.schedule(null, 200, 200,
                                                    exceptionHandler)).isInstanceOf(
                NullPointerException.class).hasMessageContaining("retryTask");

        assertThatThrownBy(() -> scheduler.schedule(task, 200, 200, null)).isInstanceOf(
                NullPointerException.class).hasMessageContaining("exceptionHandler");

        assertThatThrownBy(() -> scheduler.schedule(task, Long.MAX_VALUE - 1, Long.MAX_VALUE,
                                                    exceptionHandler)).isInstanceOf(
                IllegalArgumentException.class).hasMessageContaining("nextRetryTimeNanos");

        verifyNoMoreInteractions(task, exceptionHandler);
        verifyEventLoopScheduleCalls();
    }

    @Test
    void testScheduleTaskWithException() throws Exception {
        final Consumer<ReentrantLock> task1 = spy(dummyThrowingRetryTask);

        final long task1SchedulingTime = System.nanoTime();
        final long expectedTask1RunTime = task1SchedulingTime + TimeUnit.MILLISECONDS.toNanos(100);
        final Consumer<Throwable> task1ExceptionHandler = mock(Consumer.class);
        scheduler.schedule(task1, expectedTask1RunTime, Long.MIN_VALUE, task1ExceptionHandler);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(task1SchedulingTime, expectedTask1RunTime)
        );

        Thread.sleep(100 + SCHEDULING_TOLERANCE_MILLIS);

        verify(task1, times(1)).accept(lock);
        final ArgumentCaptor<Throwable> exceptionCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(task1ExceptionHandler, times(1)).accept(exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue()).isInstanceOf(AnticipatedException.class);
    }

    @Test
    void testEarlierRetryTaskOvertakesLaterOne() throws Exception {
        final Consumer<ReentrantLock> task1 = spy(dummyRetryTask);

        final long task1SchedulingTime = System.nanoTime();
        final long expectedTask1RunTime = task1SchedulingTime + TimeUnit.MILLISECONDS.toNanos(200);
        final Consumer<Throwable> task1ExceptionHandler = mock(Consumer.class);
        scheduler.schedule(task1, expectedTask1RunTime, Long.MIN_VALUE, task1ExceptionHandler);

        final Consumer<ReentrantLock> task2 = spy(dummyRetryTask);
        final long task2SchedulingTime = System.nanoTime();
        final long expectedTask2RunTime = task2SchedulingTime + TimeUnit.MILLISECONDS.toNanos(100);
        final Consumer<Throwable> task2ExceptionHandler = mock(Consumer.class);
        scheduler.schedule(task2, expectedTask2RunTime, Long.MIN_VALUE, task2ExceptionHandler);

        Thread.sleep(200 + SCHEDULING_TOLERANCE_MILLIS);

        verify(task1, times(0)).accept(lock);
        verify(task2, times(1)).accept(lock);

        verifyExceptionHandlerCatchedSchedulingException(task1ExceptionHandler,
                                                         RetrySchedulingException.Type.RETRY_TASK_OVERTAKEN);

        verifyNoMoreInteractions(task2ExceptionHandler);
        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(task1SchedulingTime, expectedTask1RunTime),
                EventLoopScheduleCall.of(task2SchedulingTime, expectedTask2RunTime)
        );
    }

    @Test
    void testMultipleRetryTasksBeingOvertaken() throws Exception {
        // Rationale of this test:
        // - Schedule 10 tasks with decreasing run times (1000ms, 900ms, ..., 100ms).
        // - Expect that the first 9 tasks are not executed as they are overtaken by the next task as
        //   next task is scheduled earlier.
        // - Only the last task (100ms) should be executed.

        final List<Consumer<ReentrantLock>> tasks = new ArrayList<>();
        final List<Long> schedulingTimes = new ArrayList<>();
        final List<Long> expectedRunTimes = new ArrayList<>();
        final List<Consumer<Throwable>> exceptionHandlers = new ArrayList<>();

        for (int taskNo = 0; taskNo < 10; taskNo++) {
            final Consumer<ReentrantLock> task = spy(dummyRetryTask);
            tasks.add(task);
            final Consumer<Throwable> exceptionHandler = mock(Consumer.class);
            exceptionHandlers.add(exceptionHandler);

            final long schedulingTime = System.nanoTime();
            schedulingTimes.add(schedulingTime);
            final long expectedRunTime = schedulingTime + TimeUnit.MILLISECONDS.toNanos(1000 - taskNo * 100);
            expectedRunTimes.add(expectedRunTime);

            scheduler.schedule(task, expectedRunTime, Long.MIN_VALUE, exceptionHandler);
        }

        // Wait for the tasks to be scheduled
        Thread.sleep(1000 + SCHEDULING_TOLERANCE_MILLIS);

        for (int taskNo = 0; taskNo < 9; taskNo++) {
            final Consumer<ReentrantLock> task = tasks.get(taskNo);
            verify(task, times(0)).accept(lock);
            verifyExceptionHandlerCatchedSchedulingException(
                    exceptionHandlers.get(taskNo),
                    RetrySchedulingException.Type.RETRY_TASK_OVERTAKEN
            );
        }

        // Verify that the last task was executed
        verify(tasks.get(9), times(1)).accept(lock);
        verifyNoMoreInteractions(exceptionHandlers.get(9));

        verifyEventLoopScheduleCalls(
                ImmutableList.copyOf(
                        tasks.stream()
                             .map(task -> EventLoopScheduleCall.of(schedulingTimes.get(tasks.indexOf(task)),
                                                                   expectedRunTimes.get(tasks.indexOf(task))))
                             .collect(Collectors.toList())
                )
        );
    }

    @Test
    void testLaterRetryTaskDoesNotOvertakeEarlierOne() throws Exception {
        final Consumer<ReentrantLock> task1 = spy(dummyRetryTask);
        final long task1SchedulingTime = System.nanoTime();
        final long expectedTask1RunTime = task1SchedulingTime + TimeUnit.MILLISECONDS.toNanos(200);
        final Consumer<Throwable> task1ExceptionHandler = mock(Consumer.class);
        scheduler.schedule(task1, expectedTask1RunTime, Long.MIN_VALUE, task1ExceptionHandler);

        // Schedule a new task with a later time than the current one
        final Consumer<ReentrantLock> task2 = spy(dummyRetryTask);
        final long task2SchedulingTime = System.nanoTime();
        final long expectedTask2RunTime = task2SchedulingTime + TimeUnit.MILLISECONDS.toNanos(300);
        final Consumer<Throwable> task2ExceptionHandler = mock(Consumer.class);
        scheduler.schedule(task2, expectedTask2RunTime, Long.MIN_VALUE, task2ExceptionHandler);

        Thread.sleep(300 + SCHEDULING_TOLERANCE_MILLIS);

        // Verify that the first task was executed
        verify(task1, times(1)).accept(lock);
        verifyNoMoreInteractions(task1ExceptionHandler);

        // Verify that the second task was not executed
        verify(task2, times(0)).accept(lock);
        verifyExceptionHandlerCatchedSchedulingException(
                task2ExceptionHandler, RetrySchedulingException.Type.RETRY_TASK_OVERTAKEN);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(task1SchedulingTime, expectedTask1RunTime)
        );
    }

    @Test
    void testRescheduleTaskWhenEarliestNextRetryTimeUpdated() throws Exception {
        // Set the earliest next retry time to 200ms from now
        final long now = System.nanoTime();
        final long earliestTime = now + TimeUnit.MILLISECONDS.toNanos(200);
        scheduler.addEarliestNextRetryTimeNanos(earliestTime);

        // Schedule a task to run after 300ms
        final long task1SchedulingTime = System.nanoTime();
        final long taskTime = task1SchedulingTime + TimeUnit.MILLISECONDS.toNanos(300);
        final Consumer<ReentrantLock> task1 = spy(dummyRetryTask);
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        scheduler.schedule(task1, taskTime, Long.MIN_VALUE, exceptionHandler);

        // Move the earliest next retry time to 100ms from now
        final long earliestTimeUpdateTime = System.nanoTime();
        final long newEarliestTimeNanos = now + TimeUnit.MILLISECONDS.toNanos(400);
        scheduler.addEarliestNextRetryTimeNanos(newEarliestTimeNanos);
        scheduler.rescheduleCurrentRetryTaskIfTooEarly();

        Thread.sleep(400 + SCHEDULING_TOLERANCE_MILLIS);

        verify(task1, times(1)).accept(lock);
        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(task1SchedulingTime, taskTime),
                EventLoopScheduleCall.of(earliestTimeUpdateTime, newEarliestTimeNanos)
        );
        verifyNoMoreInteractions(exceptionHandler);
    }

    @Test
    void testRescheduleWithNoTasks() throws InterruptedException {
        scheduler.rescheduleCurrentRetryTaskIfTooEarly();
        verifyEventLoopScheduleCalls();

        scheduler.addEarliestNextRetryTimeNanos(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(-100));
        scheduler.addEarliestNextRetryTimeNanos(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(0));
        scheduler.addEarliestNextRetryTimeNanos(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));

        verifyEventLoopScheduleCalls();

        scheduler.rescheduleCurrentRetryTaskIfTooEarly();

        verifyEventLoopScheduleCalls();

        final long taskScheduledTime = System.nanoTime();
        final long expectedTaskTime = taskScheduledTime + TimeUnit.MILLISECONDS.toNanos(100);

        scheduler.schedule(
                retryLock0 -> {
                    assertThat(retryLock0).isEqualTo(lock);
                    assertThat(retryLock0.isHeldByCurrentThread()).isTrue();
                    assertThat(retryLock0.getHoldCount()).isOne();
                    retryLock0.unlock();
                    // note that we inherit the earliest next retry time from the calls above
                }, taskScheduledTime + TimeUnit.MILLISECONDS.toNanos(50), Long.MIN_VALUE, t -> {
                    // do nothing
                });

        Thread.sleep(100 + SCHEDULING_TOLERANCE_MILLIS);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, expectedTaskTime)
        );

        scheduler.addEarliestNextRetryTimeNanos(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1));
        scheduler.addEarliestNextRetryTimeNanos(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, expectedTaskTime)
        );

        scheduler.rescheduleCurrentRetryTaskIfTooEarly();

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, expectedTaskTime)
        );
    }

    @Test
    void testIdempotentReschedule() throws Exception {
        // Schedule a task to run after 200ms
        final Consumer<ReentrantLock> task = spy(dummyRetryTask);
        final long taskScheduledTime = System.nanoTime();
        final long taskTime = taskScheduledTime + TimeUnit.MILLISECONDS.toNanos(100);
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        scheduler.schedule(task, taskTime, Long.MIN_VALUE, exceptionHandler);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, taskTime)
        );

        final long earliestTime1 = taskScheduledTime + TimeUnit.MILLISECONDS.toNanos(200);

        scheduler.addEarliestNextRetryTimeNanos(earliestTime1 - 100);
        scheduler.addEarliestNextRetryTimeNanos(earliestTime1);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, taskTime)
        );

        final long rescheduleTime1 = System.nanoTime();
        scheduler.rescheduleCurrentRetryTaskIfTooEarly();

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, taskTime),
                EventLoopScheduleCall.of(rescheduleTime1, earliestTime1)
        );

        scheduler.rescheduleCurrentRetryTaskIfTooEarly();
        scheduler.rescheduleCurrentRetryTaskIfTooEarly();

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, taskTime),
                EventLoopScheduleCall.of(rescheduleTime1, earliestTime1)
        );

        final long earliestTime2 = taskScheduledTime + TimeUnit.MILLISECONDS.toNanos(300);

        scheduler.addEarliestNextRetryTimeNanos(earliestTime2 - 100);
        scheduler.addEarliestNextRetryTimeNanos(earliestTime2 - 200);
        scheduler.addEarliestNextRetryTimeNanos(earliestTime2);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, taskTime),
                EventLoopScheduleCall.of(rescheduleTime1, earliestTime1)
        );

        Thread.sleep(20);

        final long rescheduleTime2 = System.nanoTime();
        scheduler.rescheduleCurrentRetryTaskIfTooEarly();

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, taskTime),
                EventLoopScheduleCall.of(rescheduleTime1, earliestTime1),
                EventLoopScheduleCall.of(rescheduleTime2, earliestTime2)
        );

        scheduler.rescheduleCurrentRetryTaskIfTooEarly();
        scheduler.rescheduleCurrentRetryTaskIfTooEarly();

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, taskTime),
                EventLoopScheduleCall.of(rescheduleTime1, earliestTime1),
                EventLoopScheduleCall.of(rescheduleTime2, earliestTime2)
        );

        Thread.sleep(300 + SCHEDULING_TOLERANCE_MILLIS);

        verify(task, times(1)).accept(lock);
        verifyNoMoreInteractions(exceptionHandler);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskScheduledTime, taskTime),
                EventLoopScheduleCall.of(rescheduleTime1, earliestTime1),
                EventLoopScheduleCall.of(rescheduleTime2, earliestTime2)
        );
    }

    @Test
    void testSchedulerShutdownWithoutTask() throws Exception {
        // Shutdown the scheduler without scheduling any tasks
        assertThat(scheduler.shutdown()).isTrue();
    }

    @Test
    void testSchedulerShutdownCancelsTask() throws Exception {
        // Schedule a task that should run after 200ms
        final Consumer<ReentrantLock> task = spy(dummyRetryTask);
        final long taskSchedulingTime = System.nanoTime();
        final long expectedTaskRunTime = taskSchedulingTime + TimeUnit.MILLISECONDS.toNanos(200);
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);
        scheduler.schedule(task, expectedTaskRunTime, Long.MIN_VALUE, exceptionHandler);

        // Shutdown the scheduler
        assertThat(scheduler.shutdown()).isTrue();

        Thread.sleep(SCHEDULING_TOLERANCE_MILLIS);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskSchedulingTime, expectedTaskRunTime)
        );

        Thread.sleep(400);

        verify(task, times(0)).accept(any());
        verifyExceptionHandlerCatchedSchedulingException(
                exceptionHandler, Type.RETRYING_ALREADY_COMPLETED);
    }

    @Test
    void testEventLoopShutdownCancelsTask() throws Exception {
        // Schedule a task that should run after 200ms
        final Consumer<ReentrantLock> task = spy(dummyRetryTask);
        final long taskSchedulingTime = System.nanoTime();
        final long expectedTaskRunTime = taskSchedulingTime + TimeUnit.MILLISECONDS.toNanos(200);
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);
        scheduler.schedule(task, expectedTaskRunTime, Long.MIN_VALUE, exceptionHandler);

        // Shutdown the event loop
        eventLoop.shutdownGracefully().sync();

        Thread.sleep(SCHEDULING_TOLERANCE_MILLIS);

        verifyEventLoopScheduleCalls(
                EventLoopScheduleCall.of(taskSchedulingTime, expectedTaskRunTime)
        );

        Thread.sleep(400);

        verify(task, times(0)).accept(any());
        verifyExceptionHandlerCatchedSchedulingException(
                exceptionHandler, Type.RETRY_TASK_CANCELLED);
    }

    @Test
    void testScheduledOnShutdownEventLoop() throws InterruptedException {
        eventLoop.shutdownGracefully().sync();

        final Consumer<ReentrantLock> task = spy(dummyRetryTask);
        final long taskSchedulingTime = System.nanoTime();
        final long expectedTaskRunTime = taskSchedulingTime + TimeUnit.MILLISECONDS.toNanos(200);
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);
        scheduler.schedule(task, expectedTaskRunTime, Long.MIN_VALUE, exceptionHandler);

        final ArgumentCaptor<Throwable> exceptionCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(exceptionHandler, times(1)).accept(exceptionCaptor.capture());
        final Throwable capturedException = exceptionCaptor.getValue();

        verify(task, times(0)).accept(lock);
        assertThat(capturedException).isInstanceOf(RejectedExecutionException.class);
        assertThat(capturedException.getMessage()).contains("event executor terminated");
    }

    // todo(szymon): add test that verifies that when a task is about to run it gets rescheduled when the
    //  earliest next retry time was set to something later in the meantime.

    private static final class EventLoopScheduleCall {
        private final long delayNanos;

        private EventLoopScheduleCall(long scheduledTimeNanos) {
            this(System.nanoTime(), scheduledTimeNanos);
        }

        private EventLoopScheduleCall(long schedulingTimeNanos, long scheduledTimeNanos) {
            assert schedulingTimeNanos <= scheduledTimeNanos : "Scheduling time must be before scheduled time";
            delayNanos = scheduledTimeNanos - schedulingTimeNanos;
        }

        public static EventLoopScheduleCall of(long scheduledTimeNanos) {
            return new EventLoopScheduleCall(scheduledTimeNanos);
        }

        public static EventLoopScheduleCall of(long schedulingTimeNanos,
                                               long scheduledTimeNanos) {
            return new EventLoopScheduleCall(schedulingTimeNanos, scheduledTimeNanos);
        }

        public long delayNanos() {
            return delayNanos;
        }
    }

    private static void verifyExceptionHandlerCatchedSchedulingException(
            Consumer<Throwable> exceptionHandler, RetrySchedulingException.Type expectedType) {
        final ArgumentCaptor<Throwable> exceptionCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(exceptionHandler, times(1)).accept(exceptionCaptor.capture());

        final Throwable capturedException = exceptionCaptor.getValue();
        assertThat(capturedException).isInstanceOf(RetrySchedulingException.class);
        assertThat(((RetrySchedulingException) capturedException).getType()).isEqualTo(expectedType);
    }

    private void verifyEventLoopScheduleCalls(EventLoopScheduleCall... expectedSchedules) {
        verifyEventLoopScheduleCalls(ImmutableList.copyOf(expectedSchedules));
    }

    private void verifyEventLoopScheduleCalls(List<EventLoopScheduleCall> expectedSchedules) {
        final int expectedNumCalls = expectedSchedules.size();

        final ArgumentCaptor<Long> scheduleDelayArgumentCaptor = ArgumentCaptor.forClass(Long.class);
        final ArgumentCaptor<TimeUnit> scheduleTimeUnitArgumentCaptor = ArgumentCaptor.forClass(TimeUnit.class);

        verify(eventLoop, times(expectedNumCalls)).schedule(any(Runnable.class),
                                                            scheduleDelayArgumentCaptor.capture(),
                                                            scheduleTimeUnitArgumentCaptor.capture());

        final List<Long> actualDelays = scheduleDelayArgumentCaptor.getAllValues();
        final List<TimeUnit> actualTimeUnits = scheduleTimeUnitArgumentCaptor.getAllValues();

        assertThat(actualDelays).hasSize(expectedNumCalls);
        assertThat(actualTimeUnits).hasSize(expectedNumCalls);

        // for simplicity, we assume all time units are NANOSECONDS
        assertThat(actualTimeUnits).allMatch(unit -> unit == TimeUnit.NANOSECONDS);

        for (int i = 0; i < expectedSchedules.size(); i++) {
            final EventLoopScheduleCall expected = expectedSchedules.get(i);
            final long actualDelayNanos = actualDelays.get(i);

            assertThat(actualDelayNanos)
                    .isBetween(expected.delayNanos() - SCHEDULING_TOLERANCE_NANOS,
                               expected.delayNanos() + SCHEDULING_TOLERANCE_NANOS);
        }
    }
}

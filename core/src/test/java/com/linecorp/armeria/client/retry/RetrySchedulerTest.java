package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;

// todo(szymon): clean up the test cases
class RetrySchedulerTest {
    private static final long SCHEDULING_TOLERANCE_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long SCHEDULING_TOLERANCE_MILLIS = TimeUnit.NANOSECONDS.toMillis(
            SCHEDULING_TOLERANCE_NANOS);

    private ArgumentCaptor<Long> scheduleDelayArgumentCaptor;
    private ArgumentCaptor<TimeUnit> scheduleTimeUnitArgumentCaptor;
    private EventLoop eventLoop;

    private RetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        eventLoop = spy(new DefaultEventLoop());
        scheduleDelayArgumentCaptor = ArgumentCaptor.forClass(Long.class);
        scheduleTimeUnitArgumentCaptor = ArgumentCaptor.forClass(TimeUnit.class);
        scheduler = new RetryScheduler(eventLoop);
    }

    @AfterEach
    void tearDown() throws Exception {
        assertThat(scheduler.close()).isTrue();
        eventLoop.shutdownGracefully().sync();
    }

    @Test
    void testEarlierRetryTaskOvertakesLaterOne() throws Exception {
        final Runnable task1 = mock(Runnable.class);

        final long task1SchedulingTime = System.nanoTime();
        final long expectedTask1RunTime = task1SchedulingTime + TimeUnit.MILLISECONDS.toNanos(200);
        final Consumer<Throwable> task1ExceptionHandler = mock(Consumer.class);
        scheduler.schedule(task1, expectedTask1RunTime, task1ExceptionHandler);

        final Runnable task2 = mock(Runnable.class);
        final long task2SchedulingTime = System.nanoTime();
        final long expectedTask2RunTime = task2SchedulingTime + TimeUnit.MILLISECONDS.toNanos(100);
        final Consumer<Throwable> task2ExceptionHandler = mock(Consumer.class);
        scheduler.schedule(task2, expectedTask2RunTime, task2ExceptionHandler);

        Thread.sleep(200 + SCHEDULING_TOLERANCE_MILLIS);

        verify(task1, times(0)).run();
        verify(task2, times(1)).run();
        verifyNoMoreInteractions(task1ExceptionHandler);
        verifyNoMoreInteractions(task2ExceptionHandler);
        verifyEventLoopSchedules(
                ImmutableList.of(
                        EventLoopScheduleCall.of(task1SchedulingTime, expectedTask1RunTime),
                        EventLoopScheduleCall.of(task2SchedulingTime, expectedTask2RunTime)
                )
        );
    }

    @Test
    void testMultipleRetryTasksBeingOvertaken() throws Exception {
        // Rationale of this test:
        // - Schedule 10 tasks with decreasing run times (1000ms, 900ms, ..., 100ms).
        // - Expect that the first 9 tasks are not executed as they are overtaken by the next task as
        //   next task is scheduled earlier.
        // - Only the last task (100ms) should be executed.

        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> schedulingTimes = new ArrayList<>();
        final List<Long> expectedRunTimes = new ArrayList<>();
        final List<Consumer<Throwable>> exceptionHandlers = new ArrayList<>();

        for (int taskNo = 0; taskNo < 10; taskNo++) {
            final Runnable task = mock(Runnable.class);
            tasks.add(task);
            final Consumer<Throwable> exceptionHandler = mock(Consumer.class);
            exceptionHandlers.add(exceptionHandler);

            final long schedulingTime = System.nanoTime();
            schedulingTimes.add(schedulingTime);
            final long expectedRunTime = schedulingTime + TimeUnit.MILLISECONDS.toNanos(1000 - taskNo * 100);
            expectedRunTimes.add(expectedRunTime);

            scheduler.schedule(task, expectedRunTime, exceptionHandler);
        }

        // Wait for the tasks to be scheduled
        Thread.sleep(1000 + SCHEDULING_TOLERANCE_MILLIS);

        for (int taskNo = 0; taskNo < 9; taskNo++) {
            final Runnable task = tasks.get(taskNo);
            verify(task, times(0)).run();
            verifyNoMoreInteractions(exceptionHandlers.get(taskNo));
        }

        // Verify that the last task was executed
        verify(tasks.get(9), times(1)).run();
        verifyNoMoreInteractions(exceptionHandlers.get(9));

        verifyEventLoopSchedules(
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
        final Runnable task1 = mock(Runnable.class);
        final long task1SchedulingTime = System.nanoTime();
        final long expectedTask1RunTime = task1SchedulingTime + TimeUnit.MILLISECONDS.toNanos(200);
        final Consumer<Throwable> task1ExceptionHandler = mock(Consumer.class);
        scheduler.schedule(task1, expectedTask1RunTime, task1ExceptionHandler);

        // Schedule a new task with a later time than the current one
        final Runnable task2 = mock(Runnable.class);
        final long task2SchedulingTime = System.nanoTime();
        final long expectedTask2RunTime = task2SchedulingTime + TimeUnit.MILLISECONDS.toNanos(300);
        final Consumer<Throwable> task2ExceptionHandler = mock(Consumer.class);
        scheduler.schedule(task2, expectedTask2RunTime, task2ExceptionHandler);

        Thread.sleep(300 + SCHEDULING_TOLERANCE_MILLIS);

        // Verify that the first task was executed
        verify(task1, times(1)).run();
        verifyNoMoreInteractions(task1ExceptionHandler);

        // Verify that the second task was not executed
        verify(task2, times(0)).run();
        verify(task2ExceptionHandler, times(1)).accept(any(IllegalStateException.class));

        verifyEventLoopSchedules(
                ImmutableList.of(
                        EventLoopScheduleCall.of(task1SchedulingTime, expectedTask1RunTime)
                        // EventLoopScheduleCall.of(task2SchedulingTime, expectedTask2RunTime)
                )
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
        final Runnable task1 = mock(Runnable.class);
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        scheduler.schedule(task1, taskTime, exceptionHandler);

        // Move the earliest next retry time to 100ms from now
        final long earliestTimeUpdateTime = System.nanoTime();
        final long newEarliestTimeNanos = now + TimeUnit.MILLISECONDS.toNanos(400);
        scheduler.addEarliestNextRetryTimeNanos(newEarliestTimeNanos);

        scheduler.rescheduleCurrentRetryTaskIfTooEarly();

        Thread.sleep(400 + SCHEDULING_TOLERANCE_MILLIS);

        verify(task1, times(1)).run();
        verifyEventLoopSchedules(
                ImmutableList.of(
                        EventLoopScheduleCall.of(task1SchedulingTime, taskTime),
                        EventLoopScheduleCall.of(earliestTimeUpdateTime, newEarliestTimeNanos)
                )
        );
        verifyNoMoreInteractions(exceptionHandler);
    }

    /**
     * Test plan 5: Verify that when a task is scheduled and then the scheduler is closed,
     * the task is cancelled and not executed.
     */
    @Test
    void testCloseSchedulerCancelsTask() throws Exception {
        final AtomicBoolean taskExecuted = new AtomicBoolean();
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        // Schedule a task to run after 200ms
        final long now = System.nanoTime();
        final long taskTime = now + TimeUnit.MILLISECONDS.toNanos(200);

        scheduler.schedule(() -> {
            taskExecuted.set(true);
        }, taskTime, exceptionHandler);

        // Close the scheduler immediately
        scheduler.close();

        // Wait a bit to ensure the task would have run if not cancelled
        // Use Awaitility to wait for a reasonable time
        await().pollDelay(300, TimeUnit.MILLISECONDS)
               .atMost(400, TimeUnit.MILLISECONDS)
               .until(() -> true); // Just wait

        // Verify that the task was not executed
        assertThat(taskExecuted.get()).isFalse();

        // Verify that the exception handler was not called
        // The scheduler mutes cancellation notifications when closed
        verifyNoMoreInteractions(exceptionHandler);
    }

    /**
     * Test with a large number of tasks (100) overtaking each other to verify that
     * the scheduler can handle a large number of tasks and that only the earliest one is executed.
     */
    @Test
    void testManyOvertakingTasks() throws Exception {

        final int numTasks = 100;
        final AtomicInteger executedTaskIndex = new AtomicInteger(-1);
        final AtomicLong executionTime = new AtomicLong();

        // Create an array of mock exception handlers
        @SuppressWarnings("unchecked")
        final Consumer<Throwable>[] exceptionHandlers = new Consumer[numTasks];
        for (int i = 0; i < numTasks; i++) {
            exceptionHandlers[i] = mock(Consumer.class);
        }

        // Schedule tasks with random times, but make sure the last one is the earliest
        final long now = System.nanoTime();
        final long baseTime = now + TimeUnit.MILLISECONDS.toNanos(200);

        // Schedule tasks in reverse order (except the last one) to ensure they're all scheduled
        // before the earliest one executes
        for (int i = numTasks - 1; i >= 0; i--) {
            final int taskIndex = i;
            final long taskTime;

            if (i == numTasks - 1) {
                // Make the last task the earliest
                taskTime = baseTime;
            } else {
                // Random time between baseTime + 50ms and baseTime + 500ms
                taskTime = baseTime + TimeUnit.MILLISECONDS.toNanos(50 + (i * 5L));
            }

            scheduler.schedule(() -> {
                executedTaskIndex.set(taskIndex);
                executionTime.set(System.nanoTime());
            }, taskTime, exceptionHandlers[i]);
        }

        // Use Awaitility to wait for a task to execute
        await().atMost(1, TimeUnit.SECONDS)
               .until(() -> executedTaskIndex.get() >= 0);

        // Verify that only the last task (index numTasks-1) was executed
        assertThat(executedTaskIndex.get()).isEqualTo(numTasks - 1);

        // Verify that the task was executed at the expected time (not too early)
        final long taskDelay = executionTime.get() - now;
        assertThat(taskDelay).isGreaterThanOrEqualTo(
                TimeUnit.MILLISECONDS.toNanos(190)); // Allow some timing flexibility

        // In this test, we can't make specific assertions about which exception handlers were called
        // because the behavior depends on the exact order of task scheduling, which can vary.
        // We only verify that at least one task was executed.
    }

    /**
     * Test that scheduling a task before the earliestNextRetryTimeNanos fails
     * and calls the exception handler with the expected exception.
     */
    @Test
    void testScheduleBeforeEarliestNextRetryTime() throws Exception {

        // Set the earliest next retry time
        final long now = System.nanoTime();
        final long earliestTime = now + TimeUnit.MILLISECONDS.toNanos(200);
        scheduler.addEarliestNextRetryTimeNanos(earliestTime);

        // Try to schedule a task before the earliest next retry time
        final long taskTime = now + TimeUnit.MILLISECONDS.toNanos(100);
        final AtomicBoolean taskExecuted = new AtomicBoolean();
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        scheduler.schedule(() -> {
            taskExecuted.set(true);
        }, taskTime, exceptionHandler);

        // Use Awaitility to wait a bit to ensure the exception handler is called
        await().pollDelay(100, TimeUnit.MILLISECONDS)
               .atMost(200, TimeUnit.MILLISECONDS)
               .until(() -> true); // Just wait

        // Verify that the task was not executed
        assertThat(taskExecuted.get()).isFalse();

        // Verify that the exception handler was called with the expected exception
        verify(exceptionHandler, times(1)).accept(any(IllegalStateException.class));
    }

    /**
     * Test that scheduling a task after the latestNextRetryTimeNanos fails
     * and calls the exception handler with the expected exception.
     */
    @Test
    void testScheduleAfterLatestNextRetryTime() throws Exception {
        // Create a scheduler with a limited latest next retry time
        final long now = System.nanoTime();
        final long latestTime = now + TimeUnit.MILLISECONDS.toNanos(200);
        final RetryScheduler scheduler = new RetryScheduler(eventLoop, latestTime);

        // Try to schedule a task after the latest next retry time
        final long taskTime = now + TimeUnit.MILLISECONDS.toNanos(300);
        final AtomicBoolean taskExecuted = new AtomicBoolean();
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        scheduler.schedule(() -> {
            taskExecuted.set(true);
        }, taskTime, exceptionHandler);

        // Use Awaitility to wait a bit to ensure the exception handler is called
        await().pollDelay(100, TimeUnit.MILLISECONDS)
               .atMost(200, TimeUnit.MILLISECONDS)
               .until(() -> true); // Just wait

        // Verify that the task was not executed
        assertThat(taskExecuted.get()).isFalse();

        // Verify that the exception handler was called with the expected exception
        verify(exceptionHandler, times(1)).accept(any(IllegalStateException.class));
    }

    /**
     * Test that tasks can be scheduled concurrently from multiple threads
     * and the scheduler correctly handles the concurrency.
     */
    @Test
    void testConcurrentScheduling() throws Exception {

        final int numThreads = 10;
        final int tasksPerThread = 10;
        final AtomicInteger executedTasks = new AtomicInteger();
        final AtomicLong earliestExecutionTime = new AtomicLong(Long.MAX_VALUE);

        // Create a latch to synchronize the start of all threads
        final CountDownLatch startLatch = new CountDownLatch(1);
        // Create a latch to wait for all threads to finish scheduling
        final CountDownLatch schedulingDoneLatch = new CountDownLatch(numThreads);

        // Create a list to store all exception handlers
        final List<Consumer<Throwable>> exceptionHandlers = new ArrayList<>();

        // Create and start threads
        final ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        final long now = System.nanoTime();

        for (int i = 0; i < numThreads; i++) {
            final int threadIndex = i;
            executorService.submit(() -> {
                try {
                    // Wait for the start signal
                    startLatch.await();

                    // Each thread schedules multiple tasks
                    for (int j = 0; j < tasksPerThread; j++) {
                        final int taskIndex = threadIndex * tasksPerThread + j;
                        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

                        synchronized (exceptionHandlers) {
                            exceptionHandlers.add(exceptionHandler);
                        }

                        // Calculate a task time - make them all different
                        // The earliest task will be the one with the smallest time
                        final long taskTime = now + TimeUnit.MILLISECONDS.toNanos(200 + taskIndex * 5);

                        scheduler.schedule(() -> {
                            executedTasks.incrementAndGet();
                            earliestExecutionTime.updateAndGet(
                                    current -> Math.min(current, System.nanoTime()));
                        }, taskTime, exceptionHandler);
                    }
                } catch (Exception e) {
                    fail("Exception in test thread: " + e.getMessage());
                } finally {
                    schedulingDoneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for all threads to finish scheduling
        schedulingDoneLatch.await();

        // Shutdown the executor service
        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.SECONDS);

        // Use Awaitility to wait for a task to execute
        await().atMost(1, TimeUnit.SECONDS)
               .until(() -> executedTasks.get() > 0);

        // Verify that exactly one task was executed
        assertThat(executedTasks.get()).isEqualTo(1);

        // Verify that the task was executed at the expected time (not too early)
        final long taskDelay = earliestExecutionTime.get() - now;
        assertThat(taskDelay).isGreaterThanOrEqualTo(
                TimeUnit.MILLISECONDS.toNanos(190)); // Allow some timing flexibility

        // Verify that at least some exception handlers were called
        // (since most tasks will be rejected due to earlier tasks being scheduled)
        int exceptionHandlerCallCount = 0;
        for (Consumer<Throwable> handler : exceptionHandlers) {
            try {
                verify(handler, times(0)).accept(any(Throwable.class));
            } catch (AssertionError e) {
                exceptionHandlerCallCount++;
            }
        }

        // We expect most tasks to be rejected, but we can't know exactly how many
        // due to the concurrent nature of the test
        assertThat(exceptionHandlerCallCount).isGreaterThan(0);
    }

    /**
     * Test that retry tasks that raise exceptions call the exception handler
     * with exactly the thrown exception.
     */
    @Test
    void testRetryTaskRaisesException() throws Exception {

        // Use a CountDownLatch to wait for the exception handler to be called
        final CountDownLatch exceptionLatch = new CountDownLatch(1);
        final AtomicReference<Throwable> caughtException = new AtomicReference<>();

        final AnticipatedException expectedException = new AnticipatedException("Test exception");

        // Schedule a task that throws an exception
        final long taskTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);

        scheduler.schedule(() -> {
            throw expectedException;
        }, taskTime, ex -> {
            caughtException.set(ex);
            exceptionLatch.countDown();
        });

        // Wait for the exception handler to be called
        assertThat(exceptionLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();

        // Verify that the exception handler was called with exactly the thrown exception
        assertThat(caughtException.get()).isSameAs(expectedException);
    }

    /**
     * Test that an exception during the eventLoop.schedule call is handled properly.
     */
    @Test
    void testExceptionDuringEventLoopSchedule() throws Exception {
        // Create a mock EventLoop that throws an exception when schedule is called
        final EventLoop mockEventLoop = mock(EventLoop.class);
        final RuntimeException expectedException = new RuntimeException("Schedule exception");
        when(mockEventLoop.schedule(any(Runnable.class), any(Long.class), any(TimeUnit.class)))
                .thenThrow(expectedException);

        final RetryScheduler scheduler = new RetryScheduler(mockEventLoop);
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        // Schedule a task (which should fail)
        final long taskTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);

        scheduler.schedule(() -> {
            fail("This task should not be executed");
        }, taskTime, exceptionHandler);

        // Verify that the exception handler was called with the expected exception
        verify(exceptionHandler, times(1)).accept(expectedException);
    }

    /**
     * Test for hasAlreadyRetryScheduledBefore method with various scenarios.
     */
    @Test
    void testHasAlreadyRetryScheduledBefore() throws Exception {

        // Case 1: No current retry task
        assertThat(scheduler.hasAlreadyRetryScheduledBefore(100, 0)).isFalse();

        // Schedule a task
        final long now = System.nanoTime();
        final long taskTime = now + TimeUnit.MILLISECONDS.toNanos(200);

        final CountDownLatch taskLatch = new CountDownLatch(1);
        scheduler.schedule(taskLatch::countDown, taskTime, ex -> {
        });

        // Case 2: Current retry task exists but is scheduled after the next retry time
        assertThat(scheduler.hasAlreadyRetryScheduledBefore(taskTime + 100, 0)).isTrue();

        // Case 3: Current retry task exists but is scheduled before the next retry time
        assertThat(scheduler.hasAlreadyRetryScheduledBefore(taskTime - 100, 0)).isFalse();

        // Case 4: Current retry task exists and is scheduled at exactly the next retry time
        assertThat(scheduler.hasAlreadyRetryScheduledBefore(taskTime, 0)).isTrue();

        // Case 5: With a non-zero earliestNextRetryTimeNanos
        final long earliestTime = taskTime + 50;
        assertThat(scheduler.hasAlreadyRetryScheduledBefore(earliestTime + 100, earliestTime)).isTrue();
        assertThat(scheduler.hasAlreadyRetryScheduledBefore(earliestTime - 100, earliestTime)).isFalse();

        // Wait for the task to complete to avoid interference with other tests
        taskLatch.await(500, TimeUnit.MILLISECONDS);
    }

    /**
     * Test for negative checkState conditions.
     */
    @Test
    void testNegativeCheckStateConditions() throws Exception {
        // Create a scheduler with a limited latest next retry time
        final long latestTime = 1000;
        final RetryScheduler scheduler = new RetryScheduler(eventLoop, latestTime);

        // Test that nextEarliestNextRetryTimeNanos > latestNextRetryTimeNanos throws IllegalStateException
        assertThatThrownBy(() -> scheduler.hasAlreadyRetryScheduledBefore(0, latestTime + 1))
                .isInstanceOf(IllegalStateException.class);

        // Test that earliestNextRetryTimeNanos > latestNextRetryTimeNanos throws IllegalStateException
        assertThatThrownBy(() -> scheduler.addEarliestNextRetryTimeNanos(latestTime + 1))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Test rescheduleCurrentRetryTaskIfTooEarly with no retry task.
     */
    @Test
    void testRescheduleCurrentRetryTaskIfTooEarlyWithNoTask() throws Exception {

        // Set the earliest next retry time
        final long earliestTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        scheduler.addEarliestNextRetryTimeNanos(earliestTime);

        // Call rescheduleCurrentRetryTaskIfTooEarly with no current retry task
        // This should not throw an exception
        scheduler.rescheduleCurrentRetryTaskIfTooEarly();

        // Verify that there is still no scheduled retry task
        assertThat(scheduler.hasScheduledRetryTask()).isFalse();
    }

    /**
     * Test rescheduleCurrentRetryTaskIfTooEarly with a retry task that doesn't need to be rescheduled.
     */
    @Test
    void testRescheduleCurrentRetryTaskIfTooEarlyWithTaskNotNeedingReschedule() throws Exception {

        // Set the earliest next retry time
        final long now = System.nanoTime();
        final long earliestTime = now + TimeUnit.MILLISECONDS.toNanos(100);
        scheduler.addEarliestNextRetryTimeNanos(earliestTime);

        // Schedule a task after the earliest next retry time
        final long taskTime = now + TimeUnit.MILLISECONDS.toNanos(200);
        final AtomicInteger taskExecutions = new AtomicInteger();
        final AtomicLong taskExecutionTime = new AtomicLong();
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        scheduler.schedule(() -> {
            taskExecutionTime.set(System.nanoTime());
            taskExecutions.incrementAndGet();
        }, taskTime, exceptionHandler);

        // Call rescheduleCurrentRetryTaskIfTooEarly
        // The task should not be rescheduled because it's already scheduled after the earliest time
        scheduler.rescheduleCurrentRetryTaskIfTooEarly();

        // Wait for the task to execute
        await()
                .until(() -> taskExecutions.get() == 1);

        // Verify that the task was executed once
        assertThat(taskExecutions.get()).isEqualTo(1);

        // Verify that the task was executed at the expected time (not too early)
        final long taskDelay = taskExecutionTime.get() - now;
        assertThat(taskDelay).isGreaterThanOrEqualTo(
                TimeUnit.MILLISECONDS.toNanos(190)); // Allow some timing flexibility

        // Verify that the exception handler was not called
        verifyNoMoreInteractions(exceptionHandler);
    }

    /**
     * Test that changing the earliest retry time without rescheduling allows
     * a task to execute before the new earliest time.
     */
    @Test
    void testChangeEarliestRetryTimeWithoutRescheduling() throws Exception {

        final AtomicInteger taskExecutions = new AtomicInteger();
        final AtomicLong taskExecutionTime = new AtomicLong();
        final Consumer<Throwable> exceptionHandler = mock(Consumer.class);

        // Schedule a task to run after 100ms
        final long now = System.nanoTime();
        final long taskTime = now + TimeUnit.MILLISECONDS.toNanos(100);

        scheduler.schedule(() -> {
            taskExecutionTime.set(System.nanoTime());
            taskExecutions.incrementAndGet();
        }, taskTime, exceptionHandler);

        // Update the earliest next retry time to be after the scheduled task
        final long newEarliestTime = now + TimeUnit.MILLISECONDS.toNanos(200);
        scheduler.addEarliestNextRetryTimeNanos(newEarliestTime);

        // Do NOT call rescheduleCurrentRetryTaskIfTooEarly()

        // Use Awaitility to wait for the task to execute
        await()
                .until(() -> taskExecutions.get() == 1);

        // Verify that the task was executed once
        assertThat(taskExecutions.get()).isEqualTo(1);

        // Verify that the task was executed at the original time (not delayed to the new earliest time)
        final long taskDelay = taskExecutionTime.get() - now;
        assertThat(taskDelay).isGreaterThanOrEqualTo(
                TimeUnit.MILLISECONDS.toNanos(90)); // Allow some timing flexibility
        assertThat(taskDelay).isLessThan(
                TimeUnit.MILLISECONDS.toNanos(190)); // Should execute before new earliest time

        // Verify that the exception handler was not called
        verifyNoMoreInteractions(exceptionHandler);
    }

    /**
     * Test for task cancellation by user (not by scheduler).
     * Since we can't directly access the RetryTaskHandle, we'll test the behavior indirectly.
     */
    @Test
    void testTaskCancellationByUser() throws Exception {
        // Use a CountDownLatch to wait for the exception handler to be called
        final CountDownLatch exceptionLatch = new CountDownLatch(1);
        final AtomicReference<Throwable> caughtException = new AtomicReference<>();

        // Create a scheduler with a real EventLoop

        final AtomicBoolean taskExecuted = new AtomicBoolean();

        // Schedule a task with a long delay
        final long taskTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10000); // 10 seconds

        // Use reflection to access the private currentRetryTask field
        final Field currentRetryTaskField;
        try {
            currentRetryTaskField = RetryScheduler.class.getDeclaredField("currentRetryTask");
            currentRetryTaskField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Could not access currentRetryTask field", e);
        }

        // Schedule the task
        scheduler.schedule(() -> {
            taskExecuted.set(true);
        }, taskTime, ex -> {
            caughtException.set(ex);
            exceptionLatch.countDown();
        });

        // Get the currentRetryTask field
        final Object currentRetryTask;
        try {
            currentRetryTask = currentRetryTaskField.get(scheduler);
            assertThat(currentRetryTask).isNotNull();
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not access currentRetryTask", e);
        }

        // Use reflection to access the scheduledFuture field
        final Field scheduledFutureField;
        try {
            // Get the RetryTaskHandle class (inner class of RetryScheduler)
            final Class<?> retryTaskHandleClass = Class.forName(
                    "com.linecorp.armeria.client.retry.RetryScheduler$RetryTaskHandle");
            scheduledFutureField = retryTaskHandleClass.getDeclaredField("scheduledFuture");
            scheduledFutureField.setAccessible(true);
        } catch (NoSuchFieldException | ClassNotFoundException e) {
            throw new RuntimeException("Could not access scheduledFuture field", e);
        }

        // Get the scheduledFuture
        final ScheduledFuture<?> scheduledFuture;
        try {
            scheduledFuture = (ScheduledFuture<?>) scheduledFutureField.get(currentRetryTask);
            assertThat((Object) scheduledFuture).isNotNull();
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not access scheduledFuture", e);
        }

        // Cancel the future directly
        scheduledFuture.cancel(false);

        // Wait for the exception handler to be called
        assertThat(exceptionLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();

        // Verify that the exception handler was called with the expected exception
        assertThat(caughtException.get()).isInstanceOf(IllegalStateException.class);
        assertThat(caughtException.get().getMessage()).contains("cancelled by the user");

        // Verify that the task was not executed
        assertThat(taskExecuted.get()).isFalse();
    }

    private static class EventLoopScheduleCall {
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

    private void verifyEventLoopSchedules(List<EventLoopScheduleCall> expectedSchedules) {
        final int expectedNumCalls = expectedSchedules.size();

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
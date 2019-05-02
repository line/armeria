/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.EventListener;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.testing.internal.AnticipatedException;
import com.linecorp.armeria.testing.junit4.common.EventLoopRule;

import io.netty.util.concurrent.FutureListener;

public class StartStopSupportTest {

    private static final String THREAD_NAME_PREFIX = StartStopSupportTest.class.getSimpleName();

    @ClassRule
    public static final EventLoopRule rule = new EventLoopRule(THREAD_NAME_PREFIX);

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Test
    public void simpleStartStop() throws Throwable {
        final StartTask startTask = SpiedStartTask.of("foo");
        final StopTask stopTask = mock(StopTask.class);
        final StartStop startStop = new StartStop(startTask, stopTask);

        assertThat(startStop.toString()).isEqualTo("STOPPED");
        assertThat(startStop.start(1, true).join()).isEqualTo("foo");
        assertThat(startStop.toString()).isEqualTo("STARTED");
        verify(startTask, times(1)).run(1);
        verify(stopTask, never()).run(any());

        assertThat(startStop.stop(2L).join()).isNull();
        assertThat(startStop.toString()).isEqualTo("STOPPED");
        verify(startTask, times(1)).run(1);
        verify(stopTask, times(1)).run(2L);
    }

    @Test
    public void startingWhileStarting() {
        final CountDownLatch startLatch = new CountDownLatch(2);
        final StartStop startStop = new StartStop(arg -> {
            // Signal the main thread that it entered the STARTING state.
            startLatch.countDown();
            startLatch.await();
            return "bar";
        }, arg -> null);

        // Enter the STARTING state.
        final CompletableFuture<String> startFuture = startStop.start(true);
        await().until(() -> startLatch.getCount() == 1);
        assertThat(startStop.toString()).isEqualTo("STARTING");

        // If 'failIfStarted' is true, start() will fail.
        assertThatThrownBy(() -> startStop.start(true).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        // If 'failIfStarted' is false, start() will return the previous future.
        assertThat(startStop.start(false)).isSameAs(startFuture);

        // Finish the startup procedure.
        startLatch.countDown();
        assertThat(startFuture.join()).isEqualTo("bar");
    }

    @Test
    public void startingWhileStarted() {
        final StartStop startStop = new StartStop(arg -> "foo", arg -> null);

        // Enter the STARTED state.
        final CompletableFuture<String> startFuture = startStop.start(true);
        assertThat(startFuture.join()).isEqualTo("foo");

        // If 'failIfStarted' is true, start() will fail.
        assertThatThrownBy(() -> startStop.start(true).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        // If 'failIfStarted' is false, start() will return the previous future.
        assertThat(startStop.start(false)).isSameAs(startFuture);
    }

    @Test
    public void startingWhileStopping() throws Throwable {
        final StartTask startTask = SpiedStartTask.of("bar");
        final CountDownLatch stopLatch = new CountDownLatch(2);
        final StartStop startStop = new StartStop(startTask, arg -> {
            // Signal the main thread that it entered the STOPPING state.
            stopLatch.countDown();
            stopLatch.await();
            return null;
        });

        // Enter the STOPPING state.
        assertThat(startStop.start(true).join()).isEqualTo("bar");
        final CompletableFuture<Void> stopFuture = startStop.stop();
        await().until(() -> stopLatch.getCount() == 1);
        assertThat(startStop.toString()).isEqualTo("STOPPING");

        // start() should never complete until shutdown procedure is complete.
        clearInvocations(startTask);
        final CompletableFuture<String> startFuture = startStop.start(true);
        repeat(() -> {
            verify(startTask, never()).run(any());
            assertThat(startFuture).isNotDone();
        });

        // Finish the shutdown procedure, so that startup procedure follows.
        stopLatch.countDown();
        assertThat(stopFuture.join()).isNull();

        // Now check that the startup procedure has been performed.
        assertThat(startFuture.join()).isEqualTo("bar");
        verify(startTask, times(1)).run(null);
    }

    @Test
    public void stoppingWhileStarting() throws Throwable {
        final StopTask stopTask = mock(StopTask.class);
        final CountDownLatch startLatch = new CountDownLatch(2);
        final StartStop startStop = new StartStop(arg -> {
            // Signal the main thread that it entered the STARTING state.
            startLatch.countDown();
            startLatch.await();
            return "foo";
        }, stopTask);

        // Enter the STARTING state.
        final CompletableFuture<String> startFuture = startStop.start(true);
        await().until(() -> startLatch.getCount() == 1);

        // stop() should never complete until startup procedure is complete.
        final CompletableFuture<Void> stopFuture = startStop.stop();
        repeat(() -> {
            verify(stopTask, never()).run(any());
            assertThat(stopFuture).isNotDone();
        });

        // Finish the startup procedure, so that shutdown procedure follows.
        startLatch.countDown();
        assertThat(startFuture.join()).isEqualTo("foo");

        // Now check that the shutdown procedure has been performed.
        assertThat(stopFuture.join()).isNull();
        verify(stopTask, times(1)).run(null);
    }

    @Test
    public void stoppingWhileStopping() {
        final AtomicLong stopArg = new AtomicLong();
        final CountDownLatch stopLatch = new CountDownLatch(2);
        final StartStop startStop = new StartStop(arg -> "bar", arg -> {
            assertThat(arg).isNotNull();
            stopArg.set(arg);

            // Signal the main thread that it entered the STOPPING state.
            stopLatch.countDown();
            stopLatch.await();
            return null;
        });

        // Enter the STOPPING state.
        assertThat(startStop.start(true).join()).isEqualTo("bar");
        final CompletableFuture<Void> stopFuture = startStop.stop(1L);
        await().until(() -> stopLatch.getCount() == 1);

        // stop() will return the previous future.
        assertThat(startStop.stop(2L)).isSameAs(stopFuture);

        // Finish the shutdown procedure.
        stopLatch.countDown();
        assertThat(stopFuture.join()).isNull();

        // Make sure doStop() was not called with the arguments of the late stop() call.
        assertThat(stopArg).hasValue(1);
    }

    @Test
    public void stoppingWhileStopped() {
        final StartStop startStop = new StartStop(arg -> "foo", arg -> null);

        // Enter the STOPPED state.
        assertThat(startStop.start(true).join()).isEqualTo("foo");
        final CompletableFuture<Void> stopFuture = startStop.stop();
        assertThat(stopFuture.join()).isNull();

        // stop() will return the previous future.
        assertThat(startStop.stop()).isSameAs(stopFuture);
    }

    @Test
    public void rollback() throws Throwable {
        final StopTask stopTask = mock(StopTask.class);
        final Exception exception = new AnticipatedException();
        final StartStop startStop = new StartStop(arg -> {
            throw exception;
        }, stopTask);

        assertThatThrownBy(() -> startStop.start(null, 1L, true).join())
                .isInstanceOf(CompletionException.class)
                .hasCause(exception);
        verify(stopTask, times(1)).run(1L);
    }

    @Test
    public void rollbackFailure() throws Throwable {
        final Exception startException = new AnticipatedException();
        final Exception stopException = new AnticipatedException();
        final StartStop startStop = spy(new StartStop(arg -> {
            throw startException;
        }, arg -> {
            throw stopException;
        }));

        assertThatThrownBy(() -> startStop.start(true).join())
                .isInstanceOf(CompletionException.class)
                .hasCause(startException);

        verify(startStop, times(1)).rollbackFailed(stopException);
    }

    @Test
    public void listenerNotifications() {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch stopLatch = new CountDownLatch(1);
        final EventListener listener = mock(EventListener.class);
        final StartStop startStop = spy(new StartStop(arg -> {
            startLatch.await();
            return "bar";
        }, arg -> {
            stopLatch.await();
            return null;
        }));
        startStop.addListener(listener);

        final CompletableFuture<String> startFuture = startStop.start(1, true);
        await().untilAsserted(() -> {
            verify(startStop, times(1)).notifyStarting(listener, 1);
            verify(startStop, never()).notifyStarted(same(listener), any(), any());
            verify(startStop, never()).notifyStopping(same(listener), any());
            verify(startStop, never()).notifyStopped(same(listener), any());
            assertThat(startFuture).isNotDone();
        });

        startLatch.countDown();
        await().untilAsserted(() -> {
            verify(startStop, times(1)).notifyStarting(listener, 1);
            verify(startStop, times(1)).notifyStarted(listener, 1, "bar");
            verify(startStop, never()).notifyStopping(same(listener), any());
            verify(startStop, never()).notifyStopped(same(listener), any());
            assertThat(startFuture).isCompletedWithValue("bar");
        });

        final CompletableFuture<Void> stopFuture = startStop.stop(2L);
        await().untilAsserted(() -> {
            verify(startStop, times(1)).notifyStarting(listener, 1);
            verify(startStop, times(1)).notifyStarted(listener, 1, "bar");
            verify(startStop, times(1)).notifyStopping(listener, 2L);
            verify(startStop, never()).notifyStopped(same(listener), any());
            assertThat(stopFuture).isNotDone();
        });

        stopLatch.countDown();
        await().untilAsserted(() -> {
            verify(startStop, times(1)).notifyStarting(listener, 1);
            verify(startStop, times(1)).notifyStarted(listener, 1, "bar");
            verify(startStop, times(1)).notifyStopping(listener, 2L);
            verify(startStop, times(1)).notifyStopped(listener, 2L);
            assertThat(stopFuture).isCompletedWithValue(null);
        });
    }

    @Test
    public void listenerNotificationFailure() throws Exception {
        final EventListener listener = mock(EventListener.class);
        final Exception exception = new AnticipatedException();
        final StartStop startStop = spy(new StartStop(arg -> "foo", arg -> null));
        doThrow(exception).when(startStop).notifyStarting(any(), any());

        startStop.addListener(listener);
        assertThat(startStop.start(true).join()).isEqualTo("foo");
        verify(startStop).notificationFailed(listener, exception);
    }

    @Test
    public void listenerRemoval() throws Exception {
        final EventListener listener = mock(EventListener.class);
        final StartStop startStop = spy(new StartStop(arg -> "bar", arg -> null));
        startStop.addListener(listener);
        startStop.removeListener(listener);

        assertThat(startStop.start(true).join()).isEqualTo("bar");
        assertThat(startStop.stop().join()).isNull();

        verify(startStop, never()).notifyStarting(same(listener), any());
        verify(startStop, never()).notifyStarted(same(listener), any(), any());
        verify(startStop, never()).notifyStopping(same(listener), any());
        verify(startStop, never()).notifyStopped(same(listener), any());
    }

    @Test
    public void close() {
        final StartStop startStop = new StartStop(arg -> "foo", arg -> null);
        startStop.close();
    }

    @Test
    public void closeWhileStopped() throws Throwable {
        final StartTask startTask = SpiedStartTask.of("bar");
        final StopTask stopTask = mock(StopTask.class);
        final StartStop startStop = new StartStop(startTask, stopTask);

        for (int i = 0; i < 2; i++) { // Check twice to ensure idempotence.
            startStop.close();
            verify(startTask, never()).run(any());
            verify(stopTask, never()).run(any());
        }
    }

    @Test
    public void closeWhileStarted() throws Throwable {
        final StartTask startTask = SpiedStartTask.of("foo");
        final StopTask stopTask = mock(StopTask.class);
        final StartStop startStop = new StartStop(startTask, stopTask);
        startStop.start(true).join();

        for (int i = 0; i < 2; i++) { // Check twice to ensure idempotence.
            verify(startTask, times(1)).run(null);
            startStop.close();
            verify(stopTask, times(1)).run(null);
        }
    }

    @Test
    public void closeFailure() {
        final Exception exception = new AnticipatedException();
        final StartStop startStop = spy(new StartStop(arg -> "bar", arg -> {
            throw exception;
        }));
        startStop.start(true).join();

        for (int i = 0; i < 2; i++) { // Check twice to ensure idempotence.
            startStop.close();
            verify(startStop, times(1)).closeFailed(exception);
        }
    }

    @Test
    public void interruptedWhileClosing() throws Throwable {
        final AtomicBoolean interrupted = new AtomicBoolean();
        final CountDownLatch stopLatch = new CountDownLatch(2);
        final StartStop startStop = new StartStop(arg -> "foo", arg -> {
            // Signal the main thread that it entered the STOPPING state.
            stopLatch.countDown();
            stopLatch.await();
            return null;
        });

        // Enter the STOPPING state.
        assertThat(startStop.start(true).join()).isEqualTo("foo");
        final Thread thread = new Thread(() -> {
            startStop.close();
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        await().until(() -> stopLatch.getCount() == 1);

        // Interrupt the thread that is blocked by close().
        thread.interrupt();

        // The interrupt should never interrupt the shutdown procedure.
        repeat(() -> assertThat(startStop.toString()).isEqualTo("STOPPING"));

        // Finish the shutdown procedure so that the close() returns.
        stopLatch.countDown();

        // Make sure the thread interruption state has been restored.
        await().untilAsserted(() -> assertThat(interrupted).isTrue());
    }

    @Test
    public void doStartReturnsNull() throws Exception {
        final StartStopSupport<Void, Void, Void, Void> startStop =
                new StartStopSupport<Void, Void, Void, Void>(rule.get()) {
                    @Override
                    protected CompletionStage<Void> doStart(@Nullable Void arg) throws Exception {
                        return null;
                    }

                    @Override
                    protected CompletionStage<Void> doStop(@Nullable Void arg) throws Exception {
                        return CompletableFuture.completedFuture(null);
                    }
                };

        assertThatThrownBy(() -> startStop.start(true).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(cause -> {
                    assertThat(cause.getCause().getMessage()).contains("doStart() returned null");
                });
    }

    @Test
    public void doStopReturnsNull() throws Exception {
        final StartStopSupport<Void, Void, String, Void> startStop =
                new StartStopSupport<Void, Void, String, Void>(rule.get()) {
                    @Override
                    protected CompletionStage<String> doStart(@Nullable Void arg) throws Exception {
                        return CompletableFuture.completedFuture("started");
                    }

                    @Override
                    protected CompletionStage<Void> doStop(@Nullable Void arg) throws Exception {
                        return null;
                    }
                };

        assertThat(startStop.start(true).join()).isEqualTo("started");
        assertThatThrownBy(() -> startStop.stop().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(cause -> {
                    assertThat(cause.getCause().getMessage()).contains("doStop() returned null");
                });
    }

    @Test
    public void rejectingExecutor() throws Exception {
        final Executor executor = mock(Executor.class);
        final StartStopSupport<Void, Void, String, Void> startStop =
                new StartStopSupport<Void, Void, String, Void>(executor) {
                    @Override
                    protected CompletionStage<String> doStart(@Nullable Void arg) throws Exception {
                        return CompletableFuture.completedFuture("started");
                    }

                    @Override
                    protected CompletionStage<Void> doStop(@Nullable Void arg) throws Exception {
                        return CompletableFuture.completedFuture(null);
                    }
                };

        // Rejected when starting.
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        assertThatThrownBy(() -> startStop.start(true).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);

        // Run the first execution so that startup succeeds.
        doAnswer(invocation -> {
            rule.get().execute(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());
        assertThat(startStop.start(true).join()).isEqualTo("started");

        // Now reject so that shutdown fails.
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        assertThatThrownBy(() -> startStop.stop().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
    }

    private static class StartStop extends StartStopSupport<Integer, Long, String, EventListener> {

        private final StartTask startTask;
        private final StopTask stopTask;

        StartStop(StartTask startTask, StopTask stopTask) {
            super(rule.get());
            this.startTask = startTask;
            this.stopTask = stopTask;
        }

        @Override
        protected CompletionStage<String> doStart(@Nullable Integer arg) throws Exception {
            return execute(startTask, arg);
        }

        @Override
        protected CompletionStage<Void> doStop(@Nullable Long arg) throws Exception {
            return execute(stopTask, arg);
        }

        private static <T, U> CompletionStage<U> execute(ThrowingFunction<T, U> task, @Nullable T arg) {
            final CompletableFuture<U> future = new CompletableFuture<>();
            rule.get().submit(() -> task.run(arg)).addListener((FutureListener<U>) f -> {
                if (f.isSuccess()) {
                    future.complete(f.getNow());
                } else {
                    future.completeExceptionally(f.cause());
                }
            });
            return future;
        }
    }

    /**
     * Keeps running the given {@code task} for a second.
     */
    private static void repeat(ThrowingCallable task) throws Throwable {
        final Stopwatch stopwatch = Stopwatch.createStarted();
        do {
            task.call();
            Thread.sleep(100);
        } while (stopwatch.elapsed(TimeUnit.MILLISECONDS) < 1000);
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, U> {
        @Nullable
        U run(@Nullable T arg) throws Exception;
    }

    @FunctionalInterface
    private interface StartTask extends ThrowingFunction<Integer, String> {}

    @FunctionalInterface
    private interface StopTask extends ThrowingFunction<Long, Void> {}

    @SuppressWarnings({
            "checkstyle:FinalClass",
            "ClassWithOnlyPrivateConstructors"
    }) // Can't be final to spy on it.
    private static class SpiedStartTask implements StartTask {

        static SpiedStartTask of(String result) {
            return spy(new SpiedStartTask(result));
        }

        private final String result;

        private SpiedStartTask(String result) {
            this.result = result;
        }

        @Override
        public String run(@Nullable Integer arg) throws Exception {
            return result;
        }
    }
}

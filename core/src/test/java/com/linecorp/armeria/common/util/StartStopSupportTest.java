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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.util.concurrent.FutureListener;

class StartStopSupportTest {

    private static final String THREAD_NAME_PREFIX = StartStopSupportTest.class.getSimpleName();

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension(
            ThreadFactories.newThreadFactory(THREAD_NAME_PREFIX, false));

    @Test
    void simpleStartStop() throws Throwable {
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
    void startingWhileStarting() {
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
    void startingWhileStarted() {
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
    void startingWhileStopping() throws Throwable {
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
    void stoppingWhileStarting() throws Throwable {
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
    void stoppingWhileStopping() {
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
    void stoppingWhileStopped() {
        final StartStop startStop = new StartStop(arg -> "foo", arg -> null);

        // Enter the STOPPED state.
        assertThat(startStop.start(true).join()).isEqualTo("foo");
        final CompletableFuture<Void> stopFuture = startStop.stop();
        assertThat(stopFuture.join()).isNull();

        // stop() will return the previous future.
        assertThat(startStop.stop()).isSameAs(stopFuture);
    }

    @Test
    void rollback() throws Throwable {
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
    void rollbackFailure() throws Throwable {
        final Exception startException = new AnticipatedException();
        final Exception stopException = new AnticipatedException();
        final List<Throwable> rollbackFailed = new ArrayList<>();
        final StartStop startStop = new StartStop(arg -> {
            throw startException;
        }, arg -> {
            throw stopException;
        }) {
            @Override
            protected void rollbackFailed(Throwable cause) {
                rollbackFailed.add(cause);
            }
        };

        assertThatThrownBy(() -> startStop.start(true).join())
                .isInstanceOf(CompletionException.class)
                .hasCause(startException);

        assertThat(rollbackFailed).containsExactly(stopException);
    }

    @Test
    void listenerNotifications() {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch stopLatch = new CountDownLatch(1);
        final EventListener listener = new EventListener() {
            @Override
            public String toString() {
                return "the_listener";
            }
        };

        final List<String> recording = new ArrayList<>();
        final StartStop startStop = new StartStop(arg -> {
            startLatch.await();
            return "bar";
        }, arg -> {
            stopLatch.await();
            return null;
        }) {
            @Override
            protected void notifyStarting(EventListener listener, @Nullable Integer arg) {
                recording.add("starting " + listener + ' ' + arg);
            }

            @Override
            protected void notifyStarted(EventListener listener,
                                         @Nullable Integer arg,
                                         @Nullable String result) {
                recording.add("started " + listener + ' ' + arg + ' ' + result);
            }

            @Override
            protected void notifyStopping(EventListener listener, @Nullable Long arg) {
                recording.add("stopping " + listener + ' ' + arg);
            }

            @Override
            protected void notifyStopped(EventListener listener, @Nullable Long arg) throws Exception {
                recording.add("stopped " + listener + ' ' + arg);
            }
        };
        startStop.addListener(listener);

        final CompletableFuture<String> startFuture = startStop.start(1, true);
        await().untilAsserted(() -> {
            assertThat(recording).containsExactly("starting the_listener 1");
            assertThat(startFuture).isNotDone();
        });

        recording.clear();
        startLatch.countDown();
        await().untilAsserted(() -> {
            assertThat(recording).containsExactly("started the_listener 1 bar");
            assertThat(startFuture).isCompletedWithValue("bar");
        });

        recording.clear();
        final CompletableFuture<Void> stopFuture = startStop.stop(2L);
        await().untilAsserted(() -> {
            assertThat(recording).containsExactly("stopping the_listener 2");
            assertThat(stopFuture).isNotDone();
        });

        recording.clear();
        stopLatch.countDown();
        await().untilAsserted(() -> {
            assertThat(recording).containsExactly("stopped the_listener 2");
            assertThat(stopFuture).isCompletedWithValue(null);
        });
    }

    @Test
    void listenerNotifyStartingFailure() throws Exception {
        final EventListener listener = mock(EventListener.class);
        final AnticipatedException exception = new AnticipatedException();
        final List<String> recording = new ArrayList<>();
        final StartStop startStop = new StartStop(arg -> "foo", arg -> null) {
            @Override
            protected void notifyStarting(EventListener listener, @Nullable Integer arg) throws Exception {
                throw exception;
            }

            @Override
            protected void notificationFailed(EventListener listener, Throwable cause) {
                recording.add(listener + " " + cause);
            }
        };

        startStop.addListener(listener);
        assertThatThrownBy(() -> startStop.start(true).join())
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCause(exception);
        assertThat(recording).containsExactly(listener + " " + exception);
    }

    @Test
    void listenerNotifyStartedFailure() throws Exception {
        final EventListener listener = mock(EventListener.class);
        final AnticipatedException exception = new AnticipatedException();
        final List<String> recording = new ArrayList<>();
        final StartStop startStop = new StartStop(arg -> "foo", arg -> null) {
            @Override
            protected void notifyStarted(EventListener listener, @Nullable Integer arg,
                                         @Nullable String result) throws Exception {
                throw exception;
            }

            @Override
            protected void notificationFailed(EventListener listener, Throwable cause) {
                recording.add(listener + " " + cause);
            }
        };

        startStop.addListener(listener);
        assertThat(startStop.start(true).join()).isEqualTo("foo");
        assertThat(recording).containsExactly(listener + " " + exception);
    }

    @Test
    void listenerRemoval() throws Exception {
        final EventListener listener = mock(EventListener.class);
        final AtomicInteger called = new AtomicInteger();
        final StartStop startStop = new StartStop(arg -> "bar", arg -> null) {
            @Override
            protected void notifyStarting(EventListener listener, @Nullable Integer arg) {
                called.incrementAndGet();
            }

            @Override
            protected void notifyStarted(EventListener listener,
                                         @Nullable Integer arg,
                                         @Nullable String result) {
                called.incrementAndGet();
            }

            @Override
            protected void notifyStopping(EventListener listener, @Nullable Long arg) {
                called.incrementAndGet();
            }

            @Override
            protected void notifyStopped(EventListener listener, @Nullable Long arg) {
                called.incrementAndGet();
            }
        };
        startStop.addListener(listener);
        startStop.removeListener(listener);

        assertThat(startStop.start(true).join()).isEqualTo("bar");
        assertThat(startStop.stop().join()).isNull();

        assertThat(called).hasValue(0);
    }

    @Test
    void close() {
        final StartStop startStop = new StartStop(arg -> "foo", arg -> null);
        startStop.close();
        assertThat(startStop.isClosing()).isTrue();
        assertThat(startStop.isClosed()).isTrue();
    }

    @Test
    void closeWhileStopped() throws Throwable {
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
    void closeWhileStarted() throws Throwable {
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
    void closeFailure() {
        final Exception exception = new AnticipatedException();
        final List<Throwable> recording = new ArrayList<>();
        final StartStop startStop = new StartStop(arg -> "bar", arg -> {
            throw exception;
        }) {
            @Override
            protected void closeFailed(Throwable cause) {
                recording.add(cause);
            }
        };
        startStop.start(true).join();
        assertThat(recording).isEmpty();

        for (int i = 0; i < 2; i++) { // Check twice to ensure idempotence.
            startStop.close();
            assertThat(recording).containsExactly(exception);
        }
    }

    @Test
    void interruptedWhileClosing() throws Throwable {
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
        assertThat(startStop.isClosing()).isTrue();
        assertThat(startStop.isClosed()).isFalse();

        // Interrupt the thread that is blocked by close().
        thread.interrupt();

        // The interrupt should never interrupt the shutdown procedure.
        repeat(() -> assertThat(startStop.toString()).isEqualTo("STOPPING"));

        // Finish the shutdown procedure so that the close() returns.
        stopLatch.countDown();

        // Make sure the thread interruption state has been restored.
        await().untilAsserted(() -> assertThat(interrupted).isTrue());
        assertThat(startStop.isClosing()).isTrue();
        assertThat(startStop.isClosed()).isTrue();
    }

    @Test
    void startAfterClose() throws Exception {
        final StartStop startStop = new StartStop(arg -> "foo", arg -> null);
        startStop.close();
        assertThatThrownBy(() -> startStop.start(false).join()).isInstanceOf(CompletionException.class)
                                                               .hasCauseInstanceOf(IllegalStateException.class)
                                                               .hasRootCauseMessage("closed already");
    }

    @Test
    void doStartReturnsNull() throws Exception {
        final StartStopSupport<Void, Void, Void, Void> startStop =
                new StartStopSupport<Void, Void, Void, Void>(eventLoop.get()) {
                    @Override
                    protected CompletionStage<Void> doStart(@Nullable Void arg) throws Exception {
                        return null;
                    }

                    @Override
                    protected CompletionStage<Void> doStop(@Nullable Void arg) throws Exception {
                        return UnmodifiableFuture.completedFuture(null);
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
    void doStopReturnsNull() throws Exception {
        final StartStopSupport<Void, Void, String, Void> startStop =
                new StartStopSupport<Void, Void, String, Void>(eventLoop.get()) {
                    @Override
                    protected CompletionStage<String> doStart(@Nullable Void arg) throws Exception {
                        return UnmodifiableFuture.completedFuture("started");
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
    void rejectingExecutor() throws Exception {
        final Executor executor = mock(Executor.class);
        final StartStopSupport<Void, Void, String, Void> startStop =
                new StartStopSupport<Void, Void, String, Void>(executor) {
                    @Override
                    protected CompletionStage<String> doStart(@Nullable Void arg) throws Exception {
                        return UnmodifiableFuture.completedFuture("started");
                    }

                    @Override
                    protected CompletionStage<Void> doStop(@Nullable Void arg) throws Exception {
                        return UnmodifiableFuture.completedFuture(null);
                    }
                };

        // Rejected when starting.
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        assertThatThrownBy(() -> startStop.start(true).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);

        // Run the first execution so that startup succeeds.
        doAnswer(invocation -> {
            eventLoop.get().execute(invocation.getArgument(0));
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
            super(eventLoop.get());
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
            eventLoop.get().submit(() -> task.run(arg)).addListener((FutureListener<U>) f -> {
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

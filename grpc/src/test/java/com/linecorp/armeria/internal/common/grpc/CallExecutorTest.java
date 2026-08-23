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

package com.linecorp.armeria.internal.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.channel.DefaultEventLoop;

class CallExecutorTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    private static ExecutorService pool;

    @BeforeAll
    static void startPool() {
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterAll
    static void stopPool() {
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------
    // Sequential variant: serializes tasks on top of a multi-threaded executor
    // (the `useBlockingTaskExecutor` mode).
    // ---------------------------------------------------------------------------------------------

    @Test
    void sequential_tasksNeverRunConcurrentlyAndKeepPerProducerOrder() throws Exception {
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final CallExecutor executor = CallExecutor.sequential(pool, errors::add);

        final int producers = 4;
        final int perProducer = 200;
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicInteger running = new AtomicInteger();
        final AtomicBoolean overlapped = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(producers * perProducer);

        final List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int producer = p;
            final Thread t = new Thread(() -> {
                for (int i = 0; i < perProducer; i++) {
                    final int seq = i;
                    executor.execute(() -> {
                        if (running.incrementAndGet() != 1) {
                            overlapped.set(true);
                        }
                        events.add(producer + ":" + seq);
                        running.decrementAndGet();
                        done.countDown();
                    });
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(overlapped).isFalse();
        assertThat(errors).isEmpty();
        // Submission order from each single producer thread must be preserved.
        for (int p = 0; p < producers; p++) {
            final String prefix = p + ":";
            int expected = 0;
            for (String e : events) {
                if (e.startsWith(prefix)) {
                    assertThat(e).isEqualTo(prefix + expected);
                    expected++;
                }
            }
            assertThat(expected).isEqualTo(perProducer);
        }
    }

    @Test
    void sequential_reentrantTaskRunsAfterCurrentTaskOnTheSameThread() throws Exception {
        final CallExecutor executor = CallExecutor.sequential(pool, t -> {});
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicReference<Thread> outerThread = new AtomicReference<>();
        final AtomicReference<Thread> innerThread = new AtomicReference<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();

        executor.execute(() -> {
            events.add("A:start");
            outerThread.set(Thread.currentThread());
            // Reentrant submission: must NOT run inline here.
            executor.execute(() -> {
                events.add("X");
                innerThread.set(Thread.currentThread());
                done.complete(null);
            });
            events.add("A:end");
        });

        done.get(10, TimeUnit.SECONDS);
        assertThat(events).containsExactly("A:start", "A:end", "X");
        assertThat(innerThread.get()).isSameAs(outerThread.get());
    }

    @Test
    void sequential_reentrantTaskDoesNotOvertakeAlreadyQueuedTasks() throws Exception {
        final CallExecutor executor = CallExecutor.sequential(pool, t -> {});
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch aStarted = new CountDownLatch(1);
        final CountDownLatch bQueued = new CountDownLatch(1);
        final CompletableFuture<Void> done = new CompletableFuture<>();

        executor.execute(() -> {
            events.add("A:start");
            aStarted.countDown();
            try {
                // Wait until the test thread has queued B behind us.
                bQueued.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Reentrant submission while B is already queued: X must run after B.
            executor.execute(() -> {
                events.add("X");
                done.complete(null);
            });
            events.add("A:end");
        });

        assertThat(aStarted.await(10, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> events.add("B"));
        bQueued.countDown();

        done.get(10, TimeUnit.SECONDS);
        assertThat(events).containsExactly("A:start", "A:end", "B", "X");
    }

    @Test
    void sequential_inExecutorIsTrueOnlyWhileRunningATask() throws Exception {
        final CallExecutor executor = CallExecutor.sequential(pool, t -> {});
        final CompletableFuture<Boolean> insideTask = new CompletableFuture<>();

        assertThat(executor.inExecutor()).isFalse();
        executor.execute(() -> insideTask.complete(executor.inExecutor()));
        assertThat(insideTask.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.inExecutor()).isFalse();
    }

    @Test
    void sequential_secondTaskDoesNotStartWhileFirstIsRunning() throws Exception {
        final CallExecutor executor = CallExecutor.sequential(pool, t -> {});
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicBoolean firstFinished = new AtomicBoolean();
        final CompletableFuture<Boolean> firstFinishedWhenSecondStarted = new CompletableFuture<>();

        executor.execute(() -> {
            firstStarted.countDown();
            try {
                releaseFirst.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            firstFinished.set(true);
        });
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // Submitted from another thread while the first task is held. The pool has idle threads, so an
        // executor that did not serialize would start it right away and it would observe the first task
        // as still running.
        executor.execute(() -> firstFinishedWhenSecondStarted.complete(firstFinished.get()));
        releaseFirst.countDown();

        assertThat(firstFinishedWhenSecondStarted.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void sequential_reentrantSubmissionDoesNotRescheduleToTheDelegate() throws Exception {
        // "Does not perform rescheduling": a reentrant submission must be picked up by the worker that is
        // already running, not handed back to the delegate executor as a new task.
        final AtomicInteger delegateCalls = new AtomicInteger();
        final Executor countingDelegate = task -> {
            delegateCalls.incrementAndGet();
            pool.execute(task);
        };
        final CallExecutor executor = CallExecutor.sequential(countingDelegate, t -> {});
        final CompletableFuture<Void> done = new CompletableFuture<>();

        executor.execute(() -> {
            executor.execute(() -> {});
            executor.execute(() -> done.complete(null));
        });

        done.get(10, TimeUnit.SECONDS);
        assertThat(delegateCalls).hasValue(1);
    }

    @Test
    void sequential_inExecutorIsFalseAgainAfterExecuteReturnsOnDirectExecutor() {
        // With a direct delegate the task runs synchronously on the calling thread, so this is the only
        // way to observe from the same thread that the "current thread" bookkeeping is reset afterwards.
        final CallExecutor executor = CallExecutor.sequential(MoreExecutors.directExecutor(), t -> {});
        final AtomicBoolean insideTask = new AtomicBoolean();

        executor.execute(() -> insideTask.set(executor.inExecutor()));

        assertThat(insideTask).isTrue();
        assertThat(executor.inExecutor()).isFalse();
    }

    @Test
    void sequential_rejectedSubmissionNeverRunsAndPropagates() {
        final ExecutorService deadPool = Executors.newSingleThreadExecutor();
        deadPool.shutdownNow();
        final CallExecutor executor = CallExecutor.sequential(deadPool, t -> {});
        final AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> executor.execute(() -> ran.set(true)))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(ran).isFalse();
    }

    @Test
    void sequential_throwingExceptionHandlerDoesNotOrphanQueuedTasks() throws Exception {
        // The handler throwing an Error is the harshest case: without isolation it would kill the worker
        // and leave the already-queued task behind forever.
        final CallExecutor executor = CallExecutor.sequential(pool, t -> {
            throw new Error("handler failed");
        });
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch secondQueued = new CountDownLatch(1);
        final CompletableFuture<Void> secondRan = new CompletableFuture<>();

        executor.execute(() -> {
            firstStarted.countDown();
            try {
                secondQueued.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("task failed");
        });
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> secondRan.complete(null));
        secondQueued.countDown();

        secondRan.get(10, TimeUnit.SECONDS);
    }

    @Test
    void sequential_exceptionIsReportedAndLaterTasksStillRun() throws Exception {
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final CallExecutor executor = CallExecutor.sequential(pool, errors::add);
        final CompletableFuture<Void> secondRan = new CompletableFuture<>();
        final RuntimeException boom = new RuntimeException("boom");

        executor.execute(() -> {
            throw boom;
        });
        executor.execute(() -> secondRan.complete(null));

        secondRan.get(10, TimeUnit.SECONDS);
        assertThat(errors).containsExactly(boom);
    }

    // ---------------------------------------------------------------------------------------------
    // Event loop variant: runs on the request's event loop (the default mode).
    // ---------------------------------------------------------------------------------------------

    @Test
    void eventLoop_runsInlineWhenCalledOnTheEventLoopWhileIdle() throws Exception {
        final CallExecutor executor = CallExecutor.of(eventLoop.get(), t -> {});
        final CompletableFuture<Boolean> ranBeforeExecuteReturned = new CompletableFuture<>();

        eventLoop.get().execute(() -> {
            final AtomicBoolean ran = new AtomicBoolean();
            executor.execute(() -> ran.set(true));
            ranBeforeExecuteReturned.complete(ran.get());
        });

        assertThat(ranBeforeExecuteReturned.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void eventLoop_reentrantTaskRunsAfterCurrentTaskInsteadOfNesting() throws Exception {
        final CallExecutor executor = CallExecutor.of(eventLoop.get(), t -> {});
        final List<String> events = new CopyOnWriteArrayList<>();
        final CompletableFuture<List<String>> afterOuterExecute = new CompletableFuture<>();

        eventLoop.get().execute(() -> {
            executor.execute(() -> {
                events.add("A:start");
                executor.execute(() -> events.add("X"));
                events.add("A:end");
            });
            // The reentrant task must have been drained before the outer execute() returned.
            afterOuterExecute.complete(new ArrayList<>(events));
        });

        assertThat(afterOuterExecute.get(10, TimeUnit.SECONDS))
                .containsExactly("A:start", "A:end", "X");
    }

    @Test
    void eventLoop_reentrantTasksKeepSubmissionOrder() throws Exception {
        final CallExecutor executor = CallExecutor.of(eventLoop.get(), t -> {});
        final List<String> events = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();

        eventLoop.get().execute(() -> {
            executor.execute(() -> {
                events.add("A:start");
                executor.execute(() -> events.add("B"));
                executor.execute(() -> events.add("X"));
                events.add("A:end");
            });
            done.complete(null);
        });

        done.get(10, TimeUnit.SECONDS);
        assertThat(events).containsExactly("A:start", "A:end", "B", "X");
    }

    @Test
    void eventLoop_reentrantTaskDoesNotOvertakeTaskQueuedByForeignThread() throws Exception {
        final CallExecutor executor = CallExecutor.of(eventLoop.get(), t -> {});
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch aStarted = new CountDownLatch(1);
        final CountDownLatch bQueued = new CountDownLatch(1);
        final CompletableFuture<Void> done = new CompletableFuture<>();

        eventLoop.get().execute(() -> executor.execute(() -> {
            events.add("A:start");
            aStarted.countDown();
            try {
                // Block the event loop briefly until the test thread has queued B from outside.
                bQueued.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Reentrant submission while B (from a foreign thread) is already queued: X must run after B.
            executor.execute(() -> {
                events.add("X");
                done.complete(null);
            });
            events.add("A:end");
        }));

        assertThat(aStarted.await(10, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> events.add("B"));
        bQueued.countDown();

        done.get(10, TimeUnit.SECONDS);
        assertThat(events).containsExactly("A:start", "A:end", "B", "X");
    }

    @Test
    void eventLoop_foreignThreadSubmissionRunsOnTheEventLoop() throws Exception {
        final CallExecutor executor = CallExecutor.of(eventLoop.get(), t -> {});
        final CompletableFuture<Boolean> ranOnEventLoop = new CompletableFuture<>();

        // The test thread is not the event loop.
        assertThat(executor.inExecutor()).isFalse();
        executor.execute(() -> ranOnEventLoop.complete(eventLoop.get().inEventLoop()));

        assertThat(ranOnEventLoop.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void eventLoop_inExecutorIsTrueOnTheEventLoop() throws Exception {
        final CallExecutor executor = CallExecutor.of(eventLoop.get(), t -> {});
        final CompletableFuture<Boolean> insideTask = new CompletableFuture<>();

        executor.execute(() -> insideTask.complete(executor.inExecutor()));
        assertThat(insideTask.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void eventLoop_rejectedForeignSubmissionNeverRunsAndLeavesNoResidue() throws Exception {
        final DefaultEventLoop deadLoop = new DefaultEventLoop();
        deadLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        final EventLoopCallExecutor executor =
                (EventLoopCallExecutor) CallExecutor.of(deadLoop, t -> {});
        final AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> executor.execute(() -> ran.set(true)))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(ran).isFalse();
        // Failure atomicity: a rejected task must not linger in the queue where a later drain could run it.
        assertThat(executor.pendingTasks()).isZero();
    }

    @Test
    void eventLoop_rejectionAfterRunningDrainTookTheTaskIsTreatedAsSuccess() throws Exception {
        // The other half of failure atomicity: if the event loop rejects the drain request only after a drain
        // that was already running has taken the task, the task did run, so execute() must return normally
        // instead of reporting a rejection the caller might retry.
        final InterceptingEventLoop loop = new InterceptingEventLoop();
        try {
            final CallExecutor executor = CallExecutor.of(loop, t -> {});
            final CountDownLatch aStarted = new CountDownLatch(1);
            final CountDownLatch releaseA = new CountDownLatch(1);
            final CompletableFuture<Void> fRan = new CompletableFuture<>();
            final AtomicInteger fRuns = new AtomicInteger();

            // A holds the event loop inside a running drain.
            loop.execute(() -> executor.execute(() -> {
                aStarted.countDown();
                try {
                    releaseA.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(aStarted.await(10, TimeUnit.SECONDS)).isTrue();

            // When the foreign submission asks the loop to drain: release A so that the running drain takes F,
            // wait until F actually ran, and only then reject the drain request.
            loop.rejectNextExecute(() -> {
                releaseA.countDown();
                fRan.join();
            });
            executor.execute(() -> {
                fRuns.incrementAndGet();
                fRan.complete(null);
            });

            assertThat(fRuns).hasValue(1);
        } finally {
            loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    void eventLoop_throwingExceptionHandlerDoesNotOrphanQueuedTasks() throws Exception {
        final CallExecutor executor = CallExecutor.of(eventLoop.get(), t -> {
            throw new Error("handler failed");
        });
        final CompletableFuture<Void> reentrantRan = new CompletableFuture<>();

        executor.execute(() -> {
            executor.execute(() -> reentrantRan.complete(null));
            throw new RuntimeException("task failed");
        });

        reentrantRan.get(10, TimeUnit.SECONDS);
    }

    @Test
    void eventLoop_exceptionIsReportedAndDrainContinues() throws Exception {
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final CallExecutor executor = CallExecutor.of(eventLoop.get(), errors::add);
        final CompletableFuture<Void> reentrantRan = new CompletableFuture<>();
        final RuntimeException boom = new RuntimeException("boom");

        executor.execute(() -> {
            executor.execute(() -> reentrantRan.complete(null));
            throw boom;
        });

        reentrantRan.get(10, TimeUnit.SECONDS);
        assertThat(errors).containsExactly(boom);
    }

    /**
     * An event loop whose next {@code execute()} can be made to run a hook and then reject.
     */
    private static final class InterceptingEventLoop extends DefaultEventLoop {

        @Nullable
        private volatile Runnable beforeReject;

        void rejectNextExecute(Runnable beforeReject) {
            this.beforeReject = beforeReject;
        }

        @Override
        public void execute(Runnable task) {
            final Runnable hook = beforeReject;
            if (hook != null) {
                beforeReject = null;
                hook.run();
                throw new RejectedExecutionException("intercepted");
            }
            super.execute(task);
        }
    }
}

/*
 * Copyright 2021 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;

/**
 * Provides an executor interface which is used for potentially long-running tasks which may block I/O threads.
 */
public interface BlockingTaskExecutor extends ScheduledExecutorService {

    /**
     * Returns a {@link BlockingTaskExecutor} with a 60s timeout and unbounded work queue.
     */
    static BlockingTaskExecutor of() {
        return BlockingTaskExecutorBuilder.of()
                                          .build();
    }

    /**
     * Returns a newly created {@link BlockingTaskExecutorBuilder}.
     */
    static BlockingTaskExecutorBuilder builder() {
        return BlockingTaskExecutorBuilder.of();
    }

    /**
     * Provides a builder that is useful for creating an {@link ScheduledExecutorService}.
     *
     * @see CommonPools#blockingTaskExecutor()
     */
    final class BlockingTaskExecutorBuilder {

        private String threadNamePrefix = "armeria-blocking-tasks";
        private int numThreads = Flags.numCommonBlockingTaskThreads();
        private long keepAliveTime = 60;
        private boolean allowCoreThreadTimeout = true;
        private boolean daemon = true;
        private int priority = Thread.NORM_PRIORITY;
        private Function<? super Runnable, ? extends Runnable> taskFunction = Function.identity();

        private BlockingTaskExecutorBuilder() {}

        /**
         * Returns a new builder which builds a new blocking task {@link ScheduledExecutorService}.
         */
        static BlockingTaskExecutorBuilder of() {
            return new BlockingTaskExecutorBuilder();
        }

        /**
         * Returns the builder {@link BlockingTaskExecutorBuilder}.
         *
         * @param threadNamePrefix the prefix of thread names
         */
        public BlockingTaskExecutorBuilder threadNamePrefix(String threadNamePrefix) {
            requireNonNull(threadNamePrefix, "threadNamePrefix");
            this.threadNamePrefix = threadNamePrefix;
            return this;
        }

        /**
         * Returns the builder {@link BlockingTaskExecutorBuilder}.
         *
         * @param numThreads the number of blocking task executor threads
         */
        public BlockingTaskExecutorBuilder numThreads(int numThreads) {
            checkArgument(numThreads >= 0, "numThreads: %s (expected: >= 0)", numThreads);
            this.numThreads = numThreads;
            return this;
        }

        /**
         * Returns the builder {@link BlockingTaskExecutorBuilder}.
         *
         * @param allowCoreThreadTimeout the flag to permit thread timeout
         */
        public BlockingTaskExecutorBuilder allowCoreThreadTimeout(boolean allowCoreThreadTimeout) {
            this.allowCoreThreadTimeout = allowCoreThreadTimeout;
            return this;
        }

        /**
         * Returns the builder {@link BlockingTaskExecutorBuilder}.
         *
         * @param keepAliveTime the amount of keep alive time in seconds
         */
        public BlockingTaskExecutorBuilder keepAliveTime(long keepAliveTime) {
            checkArgument(keepAliveTime >= 0, "keepAliveTime: %s (expected: >= 0)", keepAliveTime);
            this.keepAliveTime = keepAliveTime;
            return this;
        }

        /**
         * Returns the builder {@link BlockingTaskExecutorBuilder}.
         *
         * @param daemon the flag of daemon for new threads.
         */
        public BlockingTaskExecutorBuilder daemon(boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        /**
         * Returns the builder {@link BlockingTaskExecutorBuilder}.
         *
         * @param priority the priority for new threads.
         */
        public BlockingTaskExecutorBuilder priority(int priority) {
            checkArgument(priority >= Thread.MIN_PRIORITY && priority <= Thread.MAX_PRIORITY,
                          "priority: %s (expected: Thread.MIN_PRIORITY <= priority <= Thread.MAX_PRIORITY)",
                          priority);

            this.priority = priority;
            return this;
        }

        /**
         * Returns the builder {@link BlockingTaskExecutorBuilder}.
         * Use this method to set additional work before or after the Runnable is run. For example:
         * <pre>{@code
         * ThreadFactories.builder("thread-prefix")
         *                .taskFunction( task -> {
         *                    return () -> {
         *                        // Add something to do before task is run
         *                        task.run();
         *                        // Add something to do after task is run
         *                    };
         *                })
         *                .build();
         * }</pre>
         *
         * @param taskFunction the task function for new threads.
         */
        public BlockingTaskExecutorBuilder taskFunction(
                Function<? super Runnable, ? extends Runnable> taskFunction) {
            this.taskFunction = requireNonNull(taskFunction, "taskFunction");
            return this;
        }

        /**
         * Returns a {@link BlockingTaskExecutor} given arguments.
         */
        public BlockingTaskExecutor build() {
            final ThreadFactory threadFactory = ThreadFactories.builder(threadNamePrefix)
                                                               .daemon(daemon)
                                                               .priority(priority)
                                                               .taskFunction(taskFunction)
                                                               .build();
            final ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(
                    numThreads, threadFactory);
            scheduledThreadPoolExecutor.setKeepAliveTime(keepAliveTime, TimeUnit.SECONDS);
            scheduledThreadPoolExecutor.allowCoreThreadTimeOut(allowCoreThreadTimeout);
            return new DefaultBlockingTaskExecutor(scheduledThreadPoolExecutor);
        }

        final class DefaultBlockingTaskExecutor implements BlockingTaskExecutor {

            private ScheduledExecutorService executor;

            DefaultBlockingTaskExecutor(ScheduledThreadPoolExecutor executor) {
                this.executor = executor;
            }

            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                return executor.schedule(command, delay, unit);
            }

            @Override
            public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
                return executor.schedule(callable, delay, unit);
            }

            @Override
            public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                          TimeUnit unit) {
                return executor.scheduleAtFixedRate(command, initialDelay, period, unit);
            }

            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                             TimeUnit unit) {
                return executor.scheduleWithFixedDelay(command, initialDelay, delay, unit);
            }

            @Override
            public void shutdown() {
                executor.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return executor.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return executor.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return executor.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return executor.awaitTermination(timeout, unit);
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return executor.submit(task);
            }

            @Override
            public <T> Future<T> submit(Runnable task, T result) {
                return executor.submit(task, result);
            }

            @Override
            public Future<?> submit(Runnable task) {
                return executor.submit(task);
            }

            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                    throws InterruptedException {
                return executor.invokeAll(tasks);
            }

            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout,
                                                 TimeUnit unit) throws InterruptedException {
                return executor.invokeAll(tasks, timeout, unit);
            }

            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                    throws InterruptedException, ExecutionException {
                return executor.invokeAny(tasks);
            }

            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                return executor.invokeAny(tasks, timeout, unit);
            }

            @Override
            public void execute(Runnable command) {
                executor.execute(command);
            }
        }
    }
}

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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;

/**
 * Provides a builder that is useful for creating an BlockingTaskExecutor.
 *
 * @see CommonPools#blockingTaskExecutor()
 */
public final class BlockingTaskExecutors {

    private String threadNamePrefix = "armeria-blocking-tasks";
    private int numThreads = Flags.numCommonBlockingTaskThreads();
    private long keepAliveTime = 60;
    private boolean allowCoreThreadTimeout = true;
    private boolean daemon;
    private int priority = Thread.NORM_PRIORITY;
    private Function<? super Runnable, ? extends Runnable> taskFunction = Function.identity();

    private BlockingTaskExecutors() {}

    /**
     * Returns a new builder which builds a new blocking task {@link ScheduledExecutorService}.
     */
    public static BlockingTaskExecutors of() {
        return new BlockingTaskExecutors();
    }

    /**
     * Returns the builder {@link BlockingTaskExecutors}.
     *
     * @param threadNamePrefix the prefix of thread names
     */
    public BlockingTaskExecutors threadNamePrefix(String threadNamePrefix) {
        requireNonNull(threadNamePrefix, "threadNamePrefix");
        this.threadNamePrefix = threadNamePrefix;
        return this;
    }

    /**
     * Returns the builder {@link BlockingTaskExecutors}.
     *
     * @param numThreads the number of blocking task executor threads
     */
    public BlockingTaskExecutors numThreads(int numThreads) {
        checkArgument(numThreads >= 0, "numThreads: %s (expected: >= 0)", numThreads);
        this.numThreads = numThreads;
        return this;
    }

    /**
     * Returns the builder {@link BlockingTaskExecutors}.
     *
     * @param allowCoreThreadTimeout the flag to permit thread timeout
     */
    public BlockingTaskExecutors allowCoreThreadTimeout(boolean allowCoreThreadTimeout) {
        this.allowCoreThreadTimeout = allowCoreThreadTimeout;
        return this;
    }

    /**
     * Returns the builder {@link BlockingTaskExecutors}.
     *
     * @param keepAliveTime the amount of keep alive time in seconds
     */
    public BlockingTaskExecutors keepAliveTime(long keepAliveTime) {
        checkArgument(keepAliveTime >= 0, "keepAliveTime: %s (expected: >= 0)", keepAliveTime);
        this.keepAliveTime = keepAliveTime;
        return this;
    }

    /**
     * Returns the builder {@link BlockingTaskExecutors}
     *
     * @param daemon the flag of daemon for new threads.
     */
    public BlockingTaskExecutors daemon(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    /**
     * Returns the builder {@link BlockingTaskExecutors}
     *
     * @param priority the priority for new threads.
     */
    public BlockingTaskExecutors priority(int priority) {
        checkArgument(priority >= Thread.MIN_PRIORITY && priority <= Thread.MAX_PRIORITY,
                      "priority: %s (expected: Thread.MIN_PRIORITY <= priority <= Thread.MAX_PRIORITY)",
                      priority);

        this.priority = priority;
        return this;
    }

    /**
     * Returns the builder {@link BlockingTaskExecutors}
     *
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
    public BlockingTaskExecutors taskFunction(
            Function<? super Runnable, ? extends Runnable> taskFunction) {
        this.taskFunction = requireNonNull(taskFunction, "taskFunction");
        return this;
    }

    /**
     * Returns a blocking task {@link ScheduledExecutorService} given arguments.
     */
    public ScheduledExecutorService build() {
        final ThreadFactory threadFactory = ThreadFactories.builder(threadNamePrefix)
                                                                  .daemon(daemon)
                                                                  .priority(priority)
                                                                  .taskFunction(taskFunction)
                                                                  .build();
        final ScheduledThreadPoolExecutor blockingTaskExecutor = new ScheduledThreadPoolExecutor(
                numThreads, threadFactory);
        blockingTaskExecutor.setKeepAliveTime(keepAliveTime, TimeUnit.SECONDS);
        blockingTaskExecutor.allowCoreThreadTimeOut(allowCoreThreadTimeout);
        return blockingTaskExecutor;
    }
}

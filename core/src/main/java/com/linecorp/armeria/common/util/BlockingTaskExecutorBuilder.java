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

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;

/**
 * A builder that is useful for creating a {@link ScheduledExecutorService}.
 *
 * @see BlockingTaskExecutor#builder()
 * @see CommonPools#blockingTaskExecutor()
 */
public final class BlockingTaskExecutorBuilder {

    private String threadNamePrefix = "armeria-blocking-tasks";
    private int numThreads = Flags.numCommonBlockingTaskThreads();
    private long keepAliveTimeMillis = 60 * 1000;
    private boolean daemon = true;
    private int priority = Thread.NORM_PRIORITY;
    private Function<? super Runnable, ? extends Runnable> taskFunction = Function.identity();

    BlockingTaskExecutorBuilder() {}

    /**
     * Sets the prefix of thread names.
     */
    public BlockingTaskExecutorBuilder threadNamePrefix(String threadNamePrefix) {
        requireNonNull(threadNamePrefix, "threadNamePrefix");
        this.threadNamePrefix = threadNamePrefix;
        return this;
    }

    /**
     * Sets the number of blocking task executor threads.
     */
    public BlockingTaskExecutorBuilder numThreads(int numThreads) {
        checkArgument(numThreads >= 0, "numThreads: %s (expected: >= 0)", numThreads);
        this.numThreads = numThreads;
        return this;
    }

    /**
     * Sets the amount of keep alive time in seconds.
     */
    public BlockingTaskExecutorBuilder keepAliveTime(Duration keepAliveTime) {
        checkArgument(!requireNonNull(keepAliveTime, "keepAliveTime").isNegative(),
                      "keepAliveTime: %s (expected: >= 0)", keepAliveTime);
        return keepAliveTimeMillis(keepAliveTime.toMillis());
    }

    /**
     * Sets the amount of keep alive time in seconds.
     */
    public BlockingTaskExecutorBuilder keepAliveTimeMillis(long keepAliveTimeMillis) {
        checkArgument(keepAliveTimeMillis >= 0,
                      "keepAliveTimeMillis: %s (expected: >= 0)", keepAliveTimeMillis);
        this.keepAliveTimeMillis = keepAliveTimeMillis;
        return this;
    }

    /**
     * Sets the flag of daemon for new threads.
     */
    public BlockingTaskExecutorBuilder daemon(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    /**
     * Sets the priority for new threads.
     */
    public BlockingTaskExecutorBuilder priority(int priority) {
        checkArgument(priority >= Thread.MIN_PRIORITY && priority <= Thread.MAX_PRIORITY,
                      "priority: %s (expected: Thread.MIN_PRIORITY <= priority <= Thread.MAX_PRIORITY)",
                      priority);

        this.priority = priority;
        return this;
    }

    /**
     * Sets a function that wraps the {@link Runnable} of each new worker thread. The wrapper runs once
     * per worker thread and should invoke the worker's {@link Runnable} to process its submitted tasks.
     *
     * <p>The wrapper can initialize thread-local state when the worker starts and clean it up when it exits.
     * <pre>{@code
     * BlockingTaskExecutor.builder()
     *                     .threadNamePrefix("thread-prefix")
     *                     .taskFunction(worker -> () -> {
     *                         // Initialize state for this worker.
     *                         try {
     *                             worker.run();
     *                         } finally {
     *                             // Clean up after this worker.
     *                         }
     *                     })
     *                     .build();
     * }</pre>
     */
    public BlockingTaskExecutorBuilder taskFunction(
            Function<? super Runnable, ? extends Runnable> taskFunction) {
        this.taskFunction = requireNonNull(taskFunction, "taskFunction");
        return this;
    }

    /**
     * Returns a newly-created {@link BlockingTaskExecutor} with the properties given so far.
     */
    public BlockingTaskExecutor build() {
        final ThreadFactory threadFactory = ThreadFactories.builder(threadNamePrefix)
                                                           .daemon(daemon)
                                                           .priority(priority)
                                                           .taskFunction(taskFunction)
                                                           .build();
        final ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(
                numThreads, threadFactory);
        if (keepAliveTimeMillis > 0) {
            scheduledThreadPoolExecutor.setKeepAliveTime(keepAliveTimeMillis, TimeUnit.MILLISECONDS);
            scheduledThreadPoolExecutor.allowCoreThreadTimeOut(true);
        }
        return new DefaultBlockingTaskExecutor(scheduledThreadPoolExecutor);
    }
}

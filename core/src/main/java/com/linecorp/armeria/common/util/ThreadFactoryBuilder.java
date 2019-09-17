/*
 * Copyright 2019 LINE Corporation
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

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * Builds a new {@link ThreadFactory} instance.
 */
public final class ThreadFactoryBuilder {

    private final String threadNamePrefix;
    private final ThreadFactoryProvider threadFactoryProvider;
    private boolean daemon;
    private int priority = Thread.NORM_PRIORITY;
    private Function<? super Runnable, ? extends Runnable> taskFunction = Function.identity();

    @Nullable
    private ThreadGroup threadGroup;

    /**
     * Creates a new factory builder that creates a specified type of {@link ThreadFactory}.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param threadFactoryProvider the constructor reference of concrete {@link AbstractThreadFactory}.
     */
    ThreadFactoryBuilder(String threadNamePrefix, ThreadFactoryProvider threadFactoryProvider) {
        this.threadNamePrefix = requireNonNull(threadNamePrefix, "threadNamePrefix");
        this.threadFactoryProvider = requireNonNull(threadFactoryProvider, "threadFactoryProvider");
    }

    /**
     * Sets daemon for new threads.
     */
    public ThreadFactoryBuilder daemon(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    /**
     * Sets priority for new threads.
     */
    public ThreadFactoryBuilder priority(int priority) {
        checkArgument(priority >= Thread.MIN_PRIORITY && priority <= Thread.MAX_PRIORITY,
                      "priority: %s (expected: Thread.MIN_PRIORITY <= priority <= Thread.MAX_PRIORITY)",
                      priority);

        this.priority = priority;
        return this;
    }

    /**
     * Sets thread group for new threads.
     */
    public ThreadFactoryBuilder threadGroup(ThreadGroup threadGroup) {
        this.threadGroup = requireNonNull(threadGroup, "threadGroup");
        return this;
    }

    /**
     * Sets task function for new threads.
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
     */
    public ThreadFactoryBuilder taskFunction(
            Function<? super Runnable, ? extends Runnable> taskFunction) {
        this.taskFunction = requireNonNull(taskFunction, "taskFunction");
        return this;
    }

    /**
     * Returns a new {@link ThreadFactory} instance.
     */
    public ThreadFactory build() {
        return threadFactoryProvider.get(threadNamePrefix, daemon, priority, threadGroup, taskFunction);
    }
}

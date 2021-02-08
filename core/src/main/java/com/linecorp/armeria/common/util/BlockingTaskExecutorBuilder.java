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
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;

/**
 * Provides a builder that is useful for creating an BlockingTaskExecutor.
 *
 * @see CommonPools#blockingTaskExecutor()
 */
public final class BlockingTaskExecutorBuilder {

    private static final String DEFAULT_THREAD_NAME_PREFIX = "armeria-common-blocking-tasks";
    private static final int DEFAULT_KEEP_ALIVE_TIME_SECONDS = 60;

    private String threadNamePrefix;
    private int numThreads;
    private long keepAliveTime;
    private boolean allowCoreThreadTimeout;

    private BlockingTaskExecutorBuilder() {
        threadNamePrefix = DEFAULT_THREAD_NAME_PREFIX;
        numThreads = Flags.numCommonBlockingTaskThreads();
        keepAliveTime = DEFAULT_KEEP_ALIVE_TIME_SECONDS;
        allowCoreThreadTimeout = true;
    }

    /**
     * Returns a new builder which builds a new blocking task {@link ScheduledExecutorService}.
     */
    public static BlockingTaskExecutorBuilder builder() {
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
     * @param numThreads the number of event loop threads
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
     * Returns a blocking task {@link ScheduledExecutorService} given arguments.
     */
    public ScheduledExecutorService build() {
        final ScheduledThreadPoolExecutor blockingTaskExecutor = new ScheduledThreadPoolExecutor(
                numThreads,
                ThreadFactories.newThreadFactory(threadNamePrefix, true));
        blockingTaskExecutor.setKeepAliveTime(keepAliveTime, TimeUnit.SECONDS);
        blockingTaskExecutor.allowCoreThreadTimeOut(allowCoreThreadTimeout);
        return blockingTaskExecutor;
    }
}

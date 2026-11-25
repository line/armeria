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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledExecutorService;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Provides an executor interface which is used for potentially long-running tasks which may block I/O threads.
 */
public interface BlockingTaskExecutor extends ScheduledExecutorService, Unwrappable {

    /**
     * Returns the default {@link BlockingTaskExecutor} with a 60s timeout and unbounded work
     * queue. Note that this method returns the same instance with what
     * {@link CommonPools#blockingTaskExecutor()} returns.
     */
    static BlockingTaskExecutor of() {
        return CommonPools.blockingTaskExecutor();
    }

    /**
     * Returns a new {@link BlockingTaskExecutor} that uses the specified {@link ScheduledExecutorService}
     * to schedule and submit tasks.
     */
    static BlockingTaskExecutor of(ScheduledExecutorService executor) {
        requireNonNull(executor, "executor");
        if (executor instanceof BlockingTaskExecutor) {
            return (BlockingTaskExecutor) executor;
        } else {
            return new DefaultBlockingTaskExecutor(executor);
        }
    }

    /**
     * Returns a new builder for {@link BlockingTaskExecutor}.
     */
    static BlockingTaskExecutorBuilder builder() {
        return new BlockingTaskExecutorBuilder();
    }

    @Nullable
    @Override
    default <T> T as(Class<T> type) {
        return Unwrappable.super.as(type);
    }

    /**
     * Unwraps this {@link BlockingTaskExecutor} and returns the object being decorated.
     * If this {@link BlockingTaskExecutor} is the innermost object, this method returns itself.
     */
    @Override
    default BlockingTaskExecutor unwrap() {
        return (BlockingTaskExecutor) Unwrappable.super.unwrap();
    }
}

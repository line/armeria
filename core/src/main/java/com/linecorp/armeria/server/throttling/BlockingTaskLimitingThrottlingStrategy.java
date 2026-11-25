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

package com.linecorp.armeria.server.throttling;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.IntSupplier;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link ThrottlingStrategy} that provides a throttling strategy based on the number of pending tasks
 * queued in a {@link BlockingTaskExecutor}.
 *
 * @see ThrottlingStrategy#blockingTaskLimiting(int)
 * @see ThrottlingStrategy#blockingTaskLimiting(int, String)
 * @see ThrottlingStrategy#blockingTaskLimiting(IntSupplier)
 * @see ThrottlingStrategy#blockingTaskLimiting(IntSupplier, String)
 * @see ThrottlingStrategy#blockingTaskLimiting(BlockingTaskExecutor, int)
 * @see ThrottlingStrategy#blockingTaskLimiting(BlockingTaskExecutor, int, String)
 * @see ThrottlingStrategy#blockingTaskLimiting(BlockingTaskExecutor, IntSupplier)
 * @see ThrottlingStrategy#blockingTaskLimiting(BlockingTaskExecutor, IntSupplier, String)
 */
@UnstableApi
final class BlockingTaskLimitingThrottlingStrategy<T extends Request> extends ThrottlingStrategy<T> {

    private final ThreadPoolExecutor pool;
    private final IntSupplier limitSupplier;

    /**
     * Creates a new strategy with the specified {@link BlockingTaskExecutor}, limit, and name.
     */
    BlockingTaskLimitingThrottlingStrategy(BlockingTaskExecutor blockingTaskExecutor,
                                           IntSupplier limitSupplier,
                                           @Nullable String name) {
        super(name);
        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        this.limitSupplier = requireNonNull(limitSupplier, "limitSupplier");
        final ThreadPoolExecutor pool = blockingTaskExecutor.as(ThreadPoolExecutor.class);
        if (pool == null) {
            throw new IllegalArgumentException(
                    "blockingTaskExecutor: " + blockingTaskExecutor +
                    " (expected: unwrappable to ThreadPoolExecutor)");
        }
        this.pool = pool;
    }

    /**
     * Creates a new strategy with the specified {@link BlockingTaskExecutor} and limit.
     */
    BlockingTaskLimitingThrottlingStrategy(BlockingTaskExecutor blockingTaskExecutor,
                                           IntSupplier limitSupplier) {
        this(blockingTaskExecutor, limitSupplier, null);
    }

    @Override
    public CompletionStage<Boolean> accept(ServiceRequestContext ctx, T request) {
        final int limit = limitSupplier.getAsInt();
        if (limit <= 0 || pool.getQueue().size() >= limit) {
            return UnmodifiableFuture.completedFuture(false);
        }
        return UnmodifiableFuture.completedFuture(true);
    }
}

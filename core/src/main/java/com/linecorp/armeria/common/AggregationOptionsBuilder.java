/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.BiFunction;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A builder for {@link AggregationOptions}.
 */
@UnstableApi
public final class AggregationOptionsBuilder<T, U> {

    private final BiFunction<? super AggregationOptions<T, U>, ? super List<T>, ? extends U> aggregator;
    @Nullable
    private ByteBufAllocator alloc;
    @Nullable
    private EventExecutor executor;
    private boolean cacheResult;

    AggregationOptionsBuilder(
            BiFunction<? super AggregationOptions<T, U>, ? super List<T>, ? extends U> aggregator) {
        this.aggregator = requireNonNull(aggregator, "aggregator");
    }

    /**
     * Sets the {@link EventExecutor} to run the aggregation function on.
     */
    public AggregationOptionsBuilder<T, U> executor(EventExecutor executor) {
        requireNonNull(executor, "executor");
        this.executor = executor;
        return this;
    }

    /**
     * (Advanced users only) Sets the {@link ByteBufAllocator} to create a {@link PooledObjects} without
     * making a copy. If not specified, a {@code byte[]}-based is used to create a {@link HttpData}.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * So this method and {@link #cacheResult(boolean)} are mutually exclusive.
     * If {@link #cacheResult(boolean)} is set {@code true} and an {@link ByteBufAllocator} is set together,
     * an {@link IllegalStateException} will be raised when {@link #build()} is being called.
     */
    public AggregationOptionsBuilder<T, U> alloc(ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        this.alloc = alloc;
        return this;
    }

    /**
     * Returns whether to cache the aggregation result. This option is disabled by default.
     * Note that this method and {@link #alloc(ByteBufAllocator)} are mutually exclusive.
     * If {@link #cacheResult(boolean)} is set {@code true} and an {@link ByteBufAllocator} is set together,
     * an {@link IllegalStateException} will be raised when {@link #build()} is being called.
     *
     */
    public AggregationOptionsBuilder<T, U> cacheResult(boolean cache) {
        cacheResult = cache;
        return this;
    }

    /**
     * Returns a newly created {@link AggregationOptions} with the properties set so far.
     */
    public AggregationOptions<T, U> build() {
        if (alloc != null && cacheResult) {
            throw new IllegalStateException("Can't cache pooled objects");
        }

        EventExecutor executor = this.executor;
        if (executor == null) {
            executor = RequestContext.mapCurrent(RequestContext::eventLoop,
                                                 CommonPools.workerGroup()::next);
            assert executor != null;
        }

        return new DefaultAggregationOptions<>(aggregator, alloc, executor, cacheResult);
    }
}

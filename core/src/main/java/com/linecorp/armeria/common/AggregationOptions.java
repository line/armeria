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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link AggregationOptions} to control the aggregation behavior of {@link HttpMessage}.
 */
@UnstableApi
public interface AggregationOptions {

    /**
     * Returns a new {@link AggregationOptionsBuilder}.
     */
    static AggregationOptionsBuilder builder() {
        return new AggregationOptionsBuilder();
    }

    /**
     * Returns a new {@link AggregationOptions} that creates {@link PooledObjects} without making a copy using
     * the {@link ByteBufAllocator}.
     */
    static AggregationOptions usePooledObjects(ByteBufAllocator alloc) {
        return builder().usePooledObjects(alloc).build();
    }

    /**
     * Returns a new {@link AggregationOptions} that creates {@link PooledObjects} without making a copy using
     * the {@link ByteBufAllocator}. The specified {@link EventExecutor} is used to
     * run the aggregation function.
     */
    static AggregationOptions usePooledObjects(ByteBufAllocator alloc, EventExecutor executor) {
        return builder().usePooledObjects(alloc).executor(executor).build();
    }

    /**
     * Returns the {@link EventExecutor} that executes the aggregation.
     */
    @Nullable
    EventExecutor executor();

    /**
     * Returns whether to cache the aggregation result.
     */
    boolean cacheResult();

    /**
     * Returns whether to return the cached {@link AggregatedHttpMessage} if there's one.
     */
    boolean preferCached();

    /**
     * (Advanced users only) Returns the {@link ByteBufAllocator} that can be used to create a
     * {@link PooledObjects} without making a copy. If {@code null}, a {@code byte[]}-based {@link HttpData}
     * is created.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * Therefore, if {@link #cacheResult()} is set to {@code true}, this method always returns {@code null}.
     */
    @Nullable
    ByteBufAllocator alloc();
}

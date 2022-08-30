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

package com.linecorp.armeria.common.stream;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link AggregationOptions} to control the aggregation behavior of {@link StreamMessage}.
 * @param <T> the type of the object to aggregate.
 * @param <U> the type of the aggregated object.
 *
 * @see StreamMessage#aggregate(AggregationOptions)
 */
@UnstableApi
public interface AggregationOptions<T, U> {

    /**
     * Returns the aggregation {@link Function} that aggregates a list of {@code T} type objects into
     * a {code U} type object.
     */
    boolean isRequest();

    /**
     * Returns the {@link EventExecutor} that runs the {@link #aggregator()} on.
     */
    EventExecutor executor();

    /**
     * Returns whether to cache the aggregation result.
     */
    boolean cacheResult();

    /**
     * Returns {@code true} if an {@link HttpData} is passed to the aggregation function as is, without
     * making a copy.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * Therefore, if {@link #cacheResult()} is set to {@code true}, this method always returns {@code false}.
     */
    boolean withPooledObjects();

    /**
     * (Advanced users only) Returns the {@link ByteBufAllocator} that can be used to create a
     * {@link PooledObjects} without making a copy. If {@code null}, a {@code byte[]}-based is used to create
     * a {@link HttpData}.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * Therefore, if {@link #cacheResult()} is set to {@code true}, this method always returns {@code null}.
     */
    @Nullable
    ByteBufAllocator alloc();
}

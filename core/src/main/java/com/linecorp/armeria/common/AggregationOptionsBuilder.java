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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A builder for {@link AggregationOptions}.
 */
@UnstableApi
public final class AggregationOptionsBuilder {

    @Nullable
    private EventExecutor executor;
    @Nullable
    private ByteBufAllocator alloc;
    private boolean cacheResult;
    private boolean preferCached = true;

    AggregationOptionsBuilder() {}

    /**
     * Sets the {@link EventExecutor} to run the aggregation function.
     */
    public AggregationOptionsBuilder executor(EventExecutor executor) {
        requireNonNull(executor, "executor");
        this.executor = executor;
        return this;
    }

    /**
     * Returns whether to cache the aggregation result. This option is disabled by default.
     * Note that this method and {@link #usePooledObjects(ByteBufAllocator)} are mutually exclusive.
     * If {@link #usePooledObjects(ByteBufAllocator)}} is set and this option is enabled,
     * an {@link IllegalStateException} will be raised.
     */
    public AggregationOptionsBuilder cacheResult(boolean cache) {
        if (alloc != null) {
            throw new IllegalStateException("Can't cache pooled objects");
        }
        cacheResult = cache;
        return this;
    }

    /**
     * (Advanced users only) Sets the {@link ByteBufAllocator} that can be used to create a
     * {@link PooledObjects} without making a copy. If unspecified, a {@code byte[]}-based {@link HttpData}
     * is created.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * Therefore, this method and {@link #cacheResult(boolean)} are mutually exclusive.
     * If {@link #cacheResult(boolean)} is enabled and this option is set, an {@link IllegalStateException} will
     * be raised.
     *
     * @deprecated Use {@link #usePooledObjects(ByteBufAllocator, boolean)}.
     */
    @Deprecated
    public AggregationOptionsBuilder alloc(ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        if (cacheResult) {
            throw new IllegalStateException("Can't cache pooled objects");
        }
        this.alloc = alloc;
        return this;
    }

    /**
     * (Advanced users only) Sets the {@link ByteBufAllocator#DEFAULT} that can be used to create
     * {@link PooledObjects} without making a copy. If the {@link HttpMessage} is already aggregated and cached,
     * this option is ineffective and the cached {@link AggregatedHttpMessage} is returned.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * Therefore, this method and {@link #cacheResult(boolean)} are mutually exclusive.
     * If {@link #cacheResult(boolean)} is enabled and this option is set, an {@link IllegalStateException} will
     * be raised.
     */
    public AggregationOptionsBuilder usePooledObjects() {
        return usePooledObjects(ByteBufAllocator.DEFAULT);
    }

    /**
     * (Advanced users only) Sets the {@link ByteBufAllocator#DEFAULT} that can be used to create
     * {@link PooledObjects} without making a copy. If the {@link HttpMessage} is already aggregated and cached,
     * this option is ineffective and the cached {@link AggregatedHttpMessage} is returned.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * Therefore, this method and {@link #cacheResult(boolean)} are mutually exclusive.
     * If {@link #cacheResult(boolean)} is enabled and this option is set, an {@link IllegalStateException} will
     * be raised.
     */
    public AggregationOptionsBuilder usePooledObjects(ByteBufAllocator alloc) {
        return usePooledObjects(requireNonNull(alloc, "alloc"), true);
    }

    /**
     * (Advanced users only) Sets the {@link ByteBufAllocator} that can be used to create
     * {@link PooledObjects} without making a copy. If the {@link HttpMessage} is already aggregated and cached,
     * the cached {@link AggregatedHttpMessage} is returned if {@code preferCached} is {@code true}.
     * if {@code preferCached} is {@code false}, an {@link IllegalStateException} will be raised.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * Therefore, this method and {@link #cacheResult(boolean)} are mutually exclusive.
     * If {@link #cacheResult(boolean)} is enabled and this option is set, an {@link IllegalStateException} will
     * be raised.
     */
    public AggregationOptionsBuilder usePooledObjects(ByteBufAllocator alloc, boolean preferCached) {
        requireNonNull(alloc, "alloc");
        if (cacheResult) {
            throw new IllegalStateException("Can't cache pooled objects");
        }
        this.alloc = alloc;
        this.preferCached = preferCached;
        return this;
    }

    /**
     * Returns a newly created {@link AggregationOptions} with the properties set so far.
     */
    public AggregationOptions build() {
        return new DefaultAggregationOptions(executor, alloc, preferCached, cacheResult);
    }
}

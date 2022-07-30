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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.BiFunction;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;

/**
 * A builder for {@link AggregationOptions}.
 */
@UnstableApi
public class AggregationOptionsBuilder<T, U> {

    private final BiFunction<? super AggregationOptions<T, U>, ? super List<T>, ? extends U> aggregator;
    @Nullable
    private EventExecutor executor;
    private boolean cacheResult;
    private boolean withPooledObjects;

    /**
     * Creates an instance.
     */
    protected AggregationOptionsBuilder(
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
     * Returns the {@link EventExecutor} set via {@link #executor(EventExecutor)}.
     */
    @Nullable
    protected final EventExecutor executor() {
        return executor;
    }

    /**
     * Returns whether to cache the aggregation result. This option is disabled by default.
     * Note that this method and {@link #withPooledObjects(boolean)} are mutually exclusive.
     * If this option is enabled and {@link #withPooledObjects(boolean)} is set true,
     * an {@link IllegalStateException} will be raised when {@link #build()} is being called.
     */
    public AggregationOptionsBuilder<T, U> cacheResult(boolean cache) {
        cacheResult = cache;
        return this;
    }

    /**
     * Sets whether to cache the aggregation result.
     */
    protected final boolean cacheResult() {
        return cacheResult;
    }

    /**
     * (Advanced users only) Sets whether to receive the pooled {@link HttpData} as is, without making a copy.
     * If you don't know what this means, do not enable this option.
     * This option is disabled by default.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * So this method and {@link #cacheResult(boolean)} are mutually exclusive.
     * If {@link #cacheResult(boolean)} is set {@code true} and this option is enabled,
     * an {@link IllegalStateException} will be raised when {@link #build()} is being called.
     *
     * @see PooledObjects
     * @see SubscriptionOption#WITH_POOLED_OBJECTS
     */
    public AggregationOptionsBuilder<T, U> withPooledObjects(boolean withPooledObjects) {
        this.withPooledObjects = withPooledObjects;
        return this;
    }

    /**
     * Returns a newly created {@link AggregationOptions} with the properties set so far.
     */
    public AggregationOptions<T, U> build() {
        if (withPooledObjects && cacheResult) {
            throw new IllegalStateException("Can't cache pooled objects");
        }

        EventExecutor executor = this.executor;
        if (executor == null) {
            executor = RequestContext.mapCurrent(RequestContext::eventLoop,
                                                 CommonPools.workerGroup()::next);
            assert executor != null;
        }

        return new DefaultAggregationOptions<>(aggregator, executor, cacheResult, withPooledObjects);
    }
}

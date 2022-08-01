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
import com.linecorp.armeria.common.stream.AggregationOptions;
import com.linecorp.armeria.common.stream.AggregationOptionsBuilder;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A builder for {@link AggregationOptions}.
 */
@UnstableApi
public final class HttpAggregationOptionsBuilder<T extends HttpObject, U extends AggregatedHttpMessage>
        extends AggregationOptionsBuilder<T, U> {

    private final BiFunction<? super HttpAggregationOptions<T, U>,
            ? super List<T>, ? extends U> aggregator;
    @Nullable
    private ByteBufAllocator alloc;

    HttpAggregationOptionsBuilder(
            BiFunction<? super HttpAggregationOptions<T, U>, ? super List<T>, ? extends U> aggregator) {
        //noinspection unchecked
        super((BiFunction<? super AggregationOptions<T, U>, ? super List<T>, ? extends U>) aggregator);
        this.aggregator = aggregator;
    }

    @Override
    public HttpAggregationOptionsBuilder<T, U> executor(EventExecutor executor) {
        return (HttpAggregationOptionsBuilder<T, U>) super.executor(executor);
    }

    @Override
    public HttpAggregationOptionsBuilder<T, U> cacheResult(boolean cache) {
        return (HttpAggregationOptionsBuilder<T, U>) super.cacheResult(cache);
    }

    @Override
    public HttpAggregationOptionsBuilder<T, U> withPooledObjects(boolean withPooledObjects) {
        return withPooledObjects(withPooledObjects, ByteBufAllocator.DEFAULT);
    }

    /**
     * (Advanced users only) Sets whether to receive the pooled {@link HttpData} as is, without making a copy.
     * The {@link ByteBufAllocator} is used to create {@link PooledObjects}.
     * If you don't know what this means, do not enable this option.
     * If not specified, a {@code byte[]}-based is used to create a {@link HttpData}.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * So this method and {@link #cacheResult(boolean)} are mutually exclusive.
     * If {@link #cacheResult(boolean)} is set {@code true} and this option is enabled,
     * an {@link IllegalStateException} will be raised when {@link #build()} is being called.
     *
     * @see PooledObjects
     * @see SubscriptionOption#WITH_POOLED_OBJECTS
     */
    public HttpAggregationOptionsBuilder<T, U> withPooledObjects(boolean withPooledObjects,
                                                                 ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        super.withPooledObjects(withPooledObjects);
        this.alloc = alloc;
        return this;
    }

    /**
     * Returns a newly created {@link HttpAggregationOptions} with the properties set so far.
     * @throws IllegalStateException if the options set are invalid.
     */
    @Override
    public HttpAggregationOptions<T, U> build() {
        validateOptions();

        EventExecutor executor = executor();
        if (executor == null) {
            executor = RequestContext.mapCurrent(RequestContext::eventLoop,
                                                 CommonPools.workerGroup()::next);
        }

        return new DefaultHttpAggregationOptions<>(aggregator, executor, cacheResult(),
                                                   withPooledObjects(), alloc);
    }
}

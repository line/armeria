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

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class DefaultAggregationOptions<T, U> implements AggregationOptions<T, U> {

    private final BiFunction<? super AggregationOptions<T, U>, ? super List<T>, ? extends U> aggregator;
    @Nullable
    private final ByteBufAllocator alloc;
    private final EventExecutor executor;
    private final boolean cacheResult;

    DefaultAggregationOptions(
            BiFunction<? super AggregationOptions<T, U>, ? super List<T>, ? extends U> aggregator,
            @Nullable ByteBufAllocator alloc, EventExecutor executor, boolean cacheResult) {
        this.aggregator = aggregator;
        this.alloc = alloc;
        this.executor = executor;
        this.cacheResult = cacheResult;
    }

    @Override
    public Function<List<T>, U> aggregator() {
        return objects -> aggregator.apply(this, objects);
    }

    @Override
    public boolean cacheResult() {
        return cacheResult;
    }

    @Override
    public EventExecutor executor() {
        return executor;
    }

    @Nullable
    @Override
    public ByteBufAllocator alloc() {
        return alloc;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AggregationOptions)) {
            return false;
        }
        final AggregationOptions<?, ?> that = (AggregationOptions<?, ?>) o;
        return cacheResult == that.cacheResult() &&
               executor.equals(that.executor()) &&
               aggregator.equals(that.aggregator()) &&
               Objects.equal(alloc, that.alloc());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(aggregator, executor, alloc, cacheResult);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("aggregator", aggregator)
                          .add("executor", executor)
                          .add("alloc", alloc)
                          .add("cacheResult", cacheResult)
                          .toString();
    }
}

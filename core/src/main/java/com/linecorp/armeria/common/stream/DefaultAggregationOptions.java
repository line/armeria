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

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.base.MoreObjects;

import io.netty.util.concurrent.EventExecutor;

final class DefaultAggregationOptions<T, U> implements AggregationOptions<T, U> {

    private final BiFunction<? super AggregationOptions<T, U>, ? super List<T>, ? extends U> aggregator;
    private final EventExecutor executor;
    private final boolean cacheResult;
    private final boolean withPooledObjects;

    DefaultAggregationOptions(
            BiFunction<? super AggregationOptions<T, U>, ? super List<T>, ? extends U> aggregator,
            EventExecutor executor, boolean cacheResult, boolean withPooledObjects) {
        this.aggregator = aggregator;
        this.executor = executor;
        this.cacheResult = cacheResult;
        this.withPooledObjects = withPooledObjects;
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
    public boolean withPooledObjects() {
        return withPooledObjects;
    }

    @Override
    public EventExecutor executor() {
        return executor;
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
               withPooledObjects == that.withPooledObjects() &&
               executor.equals(that.executor()) &&
               aggregator.equals(that.aggregator());
    }

    @Override
    public int hashCode() {
        return Objects.hash(aggregator, executor, cacheResult, withPooledObjects);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("aggregator", aggregator)
                          .add("executor", executor)
                          .add("cacheResult", cacheResult)
                          .add("withPooledObjects", withPooledObjects)
                          .toString();
    }
}

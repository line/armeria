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
import java.util.function.Function;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.unsafe.PooledObjects;

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
     * Returns a new {@link AggregationOptions} with the {@link Function} that aggregates a list of
     * {@code T} type objects into a {@code U} type object.
     *
     * <p>Example:
     * <pre>{@code
     * StreamMessage stream = StreamMessage.of(1, 2, 3, 4);
     * int sum = stream.aggregate(AggregationOptions.of((nums -> nums.stream().reduce(0, Integer::sum))))
     *                 .join();
     * assert sum == 10;
     * }</pre>
     */
    static <T, U> AggregationOptions<T, U> of(Function<? super List<T>, ? extends U> aggregator) {
        return of((options, objects) -> aggregator.apply(objects));
    }

    /**
     * Returns a new {@link AggregationOptions} with the {@link BiFunction} that aggregates a list of
     * {@code T} type objects into a {@code U} type object.
     *
     * <p>Example:
     * <pre>{@code
     * StreamMessage stream = StreamMessage.of(1, 2, 3, 4);
     * int sum =
     *   stream.aggregate(AggregationOptions.of((options, nums) -> nums.stream().reduce(0, Integer::sum)))
     *         .join();
     * assert sum == 10;
     * }</pre>
     */
    static <T, U> AggregationOptions<T, U> of(
            BiFunction<? super AggregationOptions<T, U>, ? super List<T>, ? extends U> aggregator) {
        return builder(aggregator).build();
    }

    /**
     * Returns a new {@link AggregationOptionsBuilder} with the specified aggregation {@link Function}.
     */
    static <T, U> AggregationOptionsBuilder<T, U> builder(Function<? super List<T>, ? extends U> aggregator) {
        requireNonNull(aggregator, "aggregator");
        return builder((options, objects) -> aggregator.apply(objects));
    }

    /**
     * Returns a new {@link AggregationOptionsBuilder} with the specified aggregation {@link BiFunction}.
     */
    static <T, U> AggregationOptionsBuilder<T, U> builder(
            BiFunction<? super AggregationOptions<T, U>, ? super List<T>, ? extends U> aggregator) {
        return new AggregationOptionsBuilder<>(aggregator);
    }

    /**
     * Returns the aggregation {@link Function} that aggregates a list of {@code T} type objects into
     * a {code U} type object.
     */
    Function<List<T>, U> aggregator();

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
}

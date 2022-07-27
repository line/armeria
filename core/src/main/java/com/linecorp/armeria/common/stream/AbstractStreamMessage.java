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

import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.POOLED_OBJECTS;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.buffer.ByteBufAllocator;

/**
 * A skeletal {@link StreamMessage} implementation.
 */
@UnstableApi
public abstract class AbstractStreamMessage<T> implements StreamMessage<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<AbstractStreamMessage, CompletableFuture>
            aggregationUpdater = AtomicReferenceFieldUpdater.newUpdater(
            AbstractStreamMessage.class, CompletableFuture.class, "aggregation");

    @Nullable
    private volatile CompletableFuture<?> aggregation;

    /**
     * Creates a new instance.
     */
    protected AbstractStreamMessage() {}

    @Override
    public <U> CompletableFuture<U> aggregate(AggregationOptions<T, U> options) {
        final ByteBufAllocator alloc = options.alloc();
        final SubscriptionOption[] subscriptionOptions = alloc != null ? POOLED_OBJECTS : EMPTY_OPTIONS;
        if (!options.cacheResult()) {
            return collect(options.executor(), subscriptionOptions).thenApply(options.aggregator());
        }

        final CompletableFuture<?> aggregation = this.aggregation;
        if (aggregation != null) {
            //noinspection unchecked
            return (CompletableFuture<U>) aggregation;
        }

        final CompletableFuture<U> future =
                collect(options.executor(), subscriptionOptions).thenApply(options.aggregator());
        if (aggregationUpdater.compareAndSet(this, null, future)) {
            return future;
        } else {
            //noinspection ConstantConditions
            return (CompletableFuture<U>) this.aggregation;
        }
    }
}

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

package com.linecorp.armeria.internal.common.stream;

import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.POOLED_OBJECTS;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AggregationOptions;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.Exceptions;

public abstract class AggregationSupport<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<AggregationSupport, CompletableFuture>
            aggregationUpdater = AtomicReferenceFieldUpdater.newUpdater(
            AggregationSupport.class, CompletableFuture.class, "aggregation");

    @Nullable
    private volatile CompletableFuture<Object> aggregation;

    /**
     * Creates a new instance.
     */
    protected AggregationSupport() {}

    protected abstract StreamMessage<T> streamMessage();

    public <U> CompletableFuture<U> aggregate(AggregationOptions<T, U> options) {

        final boolean withPooledObjects = options.withPooledObjects();
        final SubscriptionOption[] subscriptionOptions = withPooledObjects ? POOLED_OBJECTS : EMPTY_OPTIONS;
        if (!options.cacheResult()) {
            return streamMessage().collect(options.executor(), subscriptionOptions)
                                  .thenApply(options.aggregator());
        }

        final CompletableFuture<?> aggregation = this.aggregation;
        if (aggregation != null) {
            //noinspection unchecked
            return (CompletableFuture<U>) aggregation;
        }

        if (aggregationUpdater.compareAndSet(this, null, new CompletableFuture<>())) {
            // Propagate the result to `aggregation`
            streamMessage().collect(options.executor(), subscriptionOptions).thenApply(options.aggregator())
                           .handle((res, cause) -> {
                               if (cause != null) {
                                   cause = Exceptions.peel(cause);
                                   this.aggregation.completeExceptionally(cause);
                               } else {
                                   this.aggregation.complete(res);
                               }
                               return null;
                           });
        }

        //noinspection unchecked
        return (CompletableFuture<U>) this.aggregation;
    }
}

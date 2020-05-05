/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.unsafe;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.HttpRequestAggregator;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

public interface PooledHttpRequest extends HttpRequest {

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     */
    default CompletableFuture<PooledAggregatedHttpRequest> aggregateWithPooledObjects() {
        return aggregateWithPooledObjects(defaultSubscriberExecutor());
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     */
    default CompletableFuture<PooledAggregatedHttpRequest> aggregateWithPooledObjects(EventExecutor executor) {
        requireNonNull(executor);
        return aggregateWithPooledObjects(executor, PooledByteBufAllocator.DEFAULT);
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     */
    default CompletableFuture<PooledAggregatedHttpRequest> aggregateWithPooledObjects(
            EventExecutor executor, ByteBufAllocator alloc) {
        final CompletableFuture<AggregatedHttpRequest> future = new EventLoopCheckingFuture<>();
        final HttpRequestAggregator aggregator = new HttpRequestAggregator(this, future, alloc);
        subscribe(aggregator, executor, SubscriptionOption.WITH_POOLED_OBJECTS);
        return future.thenApply(DefaultPooledAggregatedHttpRequest::new);
    }

    /**
     * @deprecated Use {@link #aggregateWithPooledObjects()}.
     */
    @Override
    @Deprecated
    CompletableFuture<AggregatedHttpRequest> aggregate();

    /**
     * @deprecated Use {@link #aggregateWithPooledObjects(EventExecutor)}.
     */
    @Override
    @Deprecated
    CompletableFuture<AggregatedHttpRequest> aggregate(EventExecutor executor);
}

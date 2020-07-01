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

package com.linecorp.armeria.common.unsafe;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.HttpResponseAggregator;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A streamed HTTP/2 {@link Response} which returns pooled buffers.
 */
public interface PooledHttpResponse extends HttpResponse, PooledStreamMessage<HttpObject> {

    /**
     * Returns a {@link PooledHttpResponse} that wraps the {@link HttpResponse}, ensuring all published data
     * is a {@link PooledHttpData}.
     */
    static PooledHttpResponse of(HttpResponse delegate) {
        requireNonNull(delegate, "delegate");
        if (delegate instanceof PooledHttpResponse) {
            return (PooledHttpResponse) delegate;
        }
        return new DefaultPooledHttpResponse(delegate);
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     */
    default CompletableFuture<PooledAggregatedHttpResponse> aggregateWithPooledObjects() {
        return aggregateWithPooledObjects(defaultSubscriberExecutor());
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     */
    default CompletableFuture<PooledAggregatedHttpResponse> aggregateWithPooledObjects(EventExecutor executor) {
        requireNonNull(executor, "executor");
        return aggregateWithPooledObjects(executor, PooledByteBufAllocator.DEFAULT);
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     */
    default CompletableFuture<PooledAggregatedHttpResponse> aggregateWithPooledObjects(ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        return aggregateWithPooledObjects(defaultSubscriberExecutor(), alloc);
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     */
    default CompletableFuture<PooledAggregatedHttpResponse> aggregateWithPooledObjects(
            EventExecutor executor, ByteBufAllocator alloc) {
        requireNonNull(executor, "executor");
        requireNonNull(alloc, "alloc");
        final CompletableFuture<AggregatedHttpResponse> future = new EventLoopCheckingFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future, alloc);
        subscribeWithPooledObjects(aggregator, executor);
        return future.thenApply(PooledAggregatedHttpResponse::of);
    }

    /**
     * Aggregate this response without pooled objects. When operating on {@link PooledHttpResponse}, this should
     * be avoided.
     *
     * @deprecated Use {@link #aggregateWithPooledObjects()}.
     */
    @Override
    @Deprecated
    default CompletableFuture<AggregatedHttpResponse> aggregate() {
        return HttpResponse.super.aggregate();
    }

    /**
     * Aggregate this response without pooled objects. When operating on {@link PooledHttpResponse}, this should
     * be avoided.
     *
     * @deprecated Use {@link #aggregateWithPooledObjects(EventExecutor)}.
     */
    @Override
    @Deprecated
    default CompletableFuture<AggregatedHttpResponse> aggregate(EventExecutor executor) {
        return HttpResponse.super.aggregate(executor);
    }
}

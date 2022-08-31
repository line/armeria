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
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpMessage;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbstractStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.HttpMessageAggregator;

import io.netty.buffer.ByteBufAllocator;

/**
 * A helper class to support caching the aggregated result of {@link HttpMessage}.
 * {@link StreamMessage} does not support aggregation because don't know how to aggregate a stream of
 * arbitrary objects in a {@link StreamMessage}.
 *
 * <p>Although this class is not directly used in {@link StreamMessage}'s API, it is injected on top of
 * {@link AbstractStreamMessage} due to the limitation of the class hierarchy so that all variants of
 * {@link HttpRequest} and {@link HttpResponse} take advantage of the caching logic in this class.
 */
public abstract class AggregationSupport {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<AggregationSupport, CompletableFuture> aggregationUpdater =
            AtomicReferenceFieldUpdater.newUpdater(AggregationSupport.class, CompletableFuture.class,
                                                   "aggregation");

    private static final CompletableFuture<Object> NO_CACHE = new CompletableFuture<>();

    protected AggregationSupport() {}

    @Nullable
    private volatile CompletableFuture<Object> aggregation;

    protected <U extends AggregatedHttpMessage> CompletableFuture<U> aggregate(AggregationOptions options) {
        requireNonNull(options, "options");

        if (!(this instanceof HttpMessage)) {
            // This API should be used internally. Should never reach here.
            throw new UnsupportedOperationException("aggregate() is only supported for " + HttpMessage.class);
        }

        final HttpMessage httpMessage = (HttpMessage) this;

        final RequestHeaders headers;
        if (httpMessage instanceof HttpRequest) {
            headers = ((HttpRequest) httpMessage).headers();
        } else {
            headers = null;
        }
        final ByteBufAllocator alloc = options.alloc();
        final SubscriptionOption[] subscriptionOptions = alloc != null ? POOLED_OBJECTS : EMPTY_OPTIONS;

        if (!options.cacheResult()) {
            if (aggregation != null) {
                throw new IllegalStateException("the stream was aggregated already. options: " + options);
            }
            if (aggregationUpdater.compareAndSet(this, null, NO_CACHE)) {
                return httpMessage.collect(options.executor(), subscriptionOptions)
                                  .thenApply(objects -> aggregate(objects, headers, alloc));
            } else {
                throw new IllegalStateException("the stream was aggregated already. options: " + options);
            }
        }

        final CompletableFuture<?> aggregation = this.aggregation;
        if (aggregation != null) {
            if (aggregation == NO_CACHE) {
                throw new IllegalStateException(
                        "the stream was aggregated previously without cache. options: " + options);
            }

            //noinspection unchecked
            return (CompletableFuture<U>) aggregation;
        }

        if (aggregationUpdater.compareAndSet(this, null, new CompletableFuture<>())) {
            // Propagate the result to `aggregation`
            httpMessage.collect(options.executor(), subscriptionOptions)
                       .thenApply(objects -> aggregate(objects, headers, alloc)).handle((res, cause) -> {
                           if (cause != null) {
                               cause = Exceptions.peel(cause);
                               this.aggregation.completeExceptionally(cause);
                           } else {
                               this.aggregation.complete(res);
                           }
                           return null;
                       });
        }

        final CompletableFuture<?> aggregation0 = this.aggregation;
        if (aggregation0 == NO_CACHE) {
            throw new IllegalStateException(
                    "the stream was aggregated previously without cache. " + "options: " + options);
        }
        //noinspection unchecked
        return (CompletableFuture<U>) aggregation0;
    }

    @SuppressWarnings("unchecked")
    private static <U extends AggregatedHttpMessage> U aggregate(List<HttpObject> objects,
                                                                 @Nullable RequestHeaders headers,
                                                                 @Nullable ByteBufAllocator allocator) {
        if (headers != null) {
            return (U) HttpMessageAggregator.aggregateRequest(headers, objects, allocator);
        } else {
            return (U) HttpMessageAggregator.aggregateResponse(objects, allocator);
        }
    }
}

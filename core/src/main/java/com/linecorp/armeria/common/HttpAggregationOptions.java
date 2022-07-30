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

import static com.linecorp.armeria.internal.common.HttpMessageAggregator.aggregateRequest;
import static com.linecorp.armeria.internal.common.HttpMessageAggregator.aggregateResponse;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.AggregationOptions;
import com.linecorp.armeria.common.stream.AggregationOptionsBuilder;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBufAllocator;

/**
 * An {@link HttpAggregationOptions} to control the aggregation behavior of {@link HttpRequest} and
 * {@link HttpResponse}.
 * @param <T> the type of the object to aggregate.
 * @param <U> the type of the aggregated object.
 */
@UnstableApi
public interface HttpAggregationOptions<T extends HttpObject, U extends AggregatedHttpMessage>
        extends AggregationOptions<T, U> {

    /**
     * Returns a new {@link HttpAggregationOptions} that aggregates an {@link HttpRequest} into an
     * {@link AggregatedHttpRequest}.
     *
     * <p>Example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * HttpRequest request = ...;
     * HttpAggregateOptions options = HttpAggregateOptions.ofRequest(request.headers());
     * AggregatedHttpRequest aggregated = request.aggregate(options).join();
     * }</pre>
     */
    static HttpAggregationOptions<HttpObject, AggregatedHttpRequest> ofRequest(RequestHeaders headers) {
        requireNonNull(headers, "headers");
        return builderForRequest(headers).build();
    }

    /**
     * Returns a new {@link HttpAggregationOptionsBuilder} that builds an {@link HttpAggregationOptions} with
     * various options to aggregate an {@link HttpRequest} into an {@link AggregatedHttpRequest}.
     *
     * <p>Example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * HttpRequest request = ...;
     * HttpAggregationOptions options =
     *     HttpAggregationOptions.builderForRequest(request.headers())
     *                           // Specify an event loop to execute the aggregation function on.
     *                           .executor(ctx.eventLoop())
     *                           // Cache the aggregated `HttpRequest`.
     *                           .cacheResult(true);
     *                           .build();
     *
     * AggregatedHttpRequest aggregated0 = request.aggregate(options).join();
     * AggregatedHttpRequest aggregated1 = request.aggregate(options).join();
     * assert aggregated0 == aggregated1;
     * }</pre>
     */
    static HttpAggregationOptionsBuilder<HttpObject, AggregatedHttpRequest> builderForRequest(
            RequestHeaders headers) {
        requireNonNull(headers, "headers");
        return new HttpAggregationOptionsBuilder<>((options, objects) -> aggregateRequest(headers, objects,
                                                                                          options.alloc()));
    }

    /**
     * Returns a new {@link HttpAggregationOptions} that aggregates an {@link HttpResponse} into
     * an {@link AggregatedHttpResponse}.
     *
     * <p>Example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * HttpResponse response = ...;
     * HttpAggregateOptions options = HttpAggregateOptions.ofResponse();
     * AggregatedHttpResponse aggregated = response.aggregate(options).join();
     * }</pre>
     */
    static HttpAggregationOptions<HttpObject, AggregatedHttpResponse> ofResponse() {
        return builderForResponse().build();
    }

    /**
     * Returns a new {@link AggregationOptionsBuilder} that builds an {@link AggregationOptions} with various
     * options to aggregate an {@link HttpResponse} into an {@link AggregatedHttpResponse}.
     *
     * <p>Example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * HttpResponse response = ...;
     * HttpAggregateOptions options =
     *     HttpAggregateOptions.builderForResponse()
     *                         // Pooled objects are used to aggregated the `HttpResponse`.
     *                         .alloc(ctx.alloc())
     *                         // Pooled objects are used to aggregated the `HttpResponse`.
     *                         .withPooledObjects(true)
     *                         // Specify an event loop to execute the aggregation function on.
     *                         .executor(ctx.eventLoop())
     *                         // Cache the aggregated `HttpResponse`.
     *                         .cacheResult(true);
     *                         .build();
     *
     * AggregatedHttpResponse aggregated0 = response.aggregate(options).join();
     * AggregatedHttpResponse aggregated1 = response.aggregate(options).join();
     * assert aggregated0 == aggregated1;
     * }</pre>
     */
    static HttpAggregationOptionsBuilder<HttpObject, AggregatedHttpResponse> builderForResponse() {
        return new HttpAggregationOptionsBuilder<>((options, objects) -> aggregateResponse(objects,
                                                                                           options.alloc()));
    }

    /**
     * (Advanced users only) Returns the {@link ByteBufAllocator} that can be used to create a
     * {@link PooledObjects} without making a copy. If {@code null}, a {@code byte[]}-based is used to create
     * a {@link HttpData}.
     *
     * <p>{@link PooledObjects} cannot be cached since they have their own life cycle.
     * Therefore, if {@link #cacheResult()} is set to {@code true}, this method always returns {@code null}.
     */
    @Nullable
    ByteBufAllocator alloc();
}

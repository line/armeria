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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.ResponseAsUtil.aggregateAndConvert;
import static com.spotify.futures.CompletableFutures.allAsList;
import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.JacksonObjectMapperProvider;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.StreamMessages;

/**
 * Transforms a response into another.
 */
@UnstableApi
@FunctionalInterface
public interface ResponseAs<T, R> {

    /**
     * Aggregates an {@link HttpResponse} and waits the result of {@link HttpResponse#aggregate()}.
     */
    static ResponseAs<HttpResponse, AggregatedHttpResponse> blocking() {
        return ResponseAsUtil.BLOCKING;
    }

    /**
     * Aggregates an {@link HttpResponse} and convert the {@link AggregatedHttpResponse#content()} into bytes.
     */
    static FutureResponseAs<ResponseEntity<byte[]>> bytes() {
        return aggregateAndConvert(AggregatedResponseAs.bytes());
    }

    /**
     * Aggregates an {@link HttpResponse} and convert the {@link AggregatedHttpResponse#content()} into
     * {@link String}.
     */
    static FutureResponseAs<ResponseEntity<String>> string() {
        return aggregateAndConvert(AggregatedResponseAs.string());
    }

    /**
     * Writes the content of an {@link HttpResponse} into the specified {@link Path}.
     */
    static FutureResponseAs<ResponseEntity<Path>> path(Path path) {
        requireNonNull(path, "path");
        return response -> {
            final SplitHttpResponse splitResponse = response.split();
            final CompletableFuture<Void> future = StreamMessages.writeTo(splitResponse.body(), path);

            return allAsList(ImmutableList.of(splitResponse.headers(), future, splitResponse.trailers()))
                    .thenApply(objects -> {
                        final ResponseHeaders headers = (ResponseHeaders) objects.get(0);
                        final HttpHeaders trailers = (HttpHeaders) objects.get(2);
                        return ResponseEntity.of(headers, path, trailers);
                    });
        };
    }

    /**
     * Aggregates an {@link HttpResponse} and deserialize the JSON {@link AggregatedHttpResponse#content()} into
     * the specified non-container type using the default {@link ObjectMapper}.
     *
     * <p>Note that this method should NOT be used if the result type is a container ({@link Collection} or
     * {@link Map}.
     *
     * @see JacksonObjectMapperProvider
     */
    static <T> FutureResponseAs<ResponseEntity<T>> json(Class<? extends T> clazz) {
        requireNonNull(clazz, "clazz");
        return aggregateAndConvert(AggregatedResponseAs.json(clazz));
    }

    /**
     * Aggregates an {@link HttpResponse} and deserialize the JSON {@link AggregatedHttpResponse#content()} into
     * the specified non-container type using the specified {@link ObjectMapper}.
     *
     * <p>Note that this method should NOT be used if the result type is a container ({@link Collection} or
     * {@link Map}.
     */
    static <T> FutureResponseAs<ResponseEntity<T>> json(Class<? extends T> clazz, ObjectMapper mapper) {
        requireNonNull(clazz, "clazz");
        requireNonNull(mapper, "mapper");
        return aggregateAndConvert(AggregatedResponseAs.json(clazz, mapper));
    }

    /**
     * Aggregates an {@link HttpResponse} and deserialize the JSON {@link AggregatedHttpResponse#content()} into
     * the specified Java type using the default {@link ObjectMapper}.
     *
     * @see JacksonObjectMapperProvider
     */
    static <T> FutureResponseAs<ResponseEntity<T>> json(TypeReference<? extends T> typeRef) {
        requireNonNull(typeRef, "typeRef");
        return aggregateAndConvert(AggregatedResponseAs.json(typeRef));
    }

    /**
     * Aggregates an {@link HttpResponse} and deserialize the JSON {@link AggregatedHttpResponse#content()} into
     * the specified Java type using the specified {@link ObjectMapper}.
     */
    static <T> FutureResponseAs<ResponseEntity<T>> json(TypeReference<? extends T> typeRef,
                                                        ObjectMapper mapper) {
        requireNonNull(typeRef, "typeRef");
        requireNonNull(mapper, "mapper");
        return aggregateAndConvert(AggregatedResponseAs.json(typeRef, mapper));
    }

    /**
     * Transforms the response into another.
     */
    R as(T response);

    /**
     * Returns whether the response should be aggregated.
     */
    default boolean aggregationRequired() {
        return false;
    }

    /**
     * Returns a composed {@link ResponseAs} that first applies this {@link ResponseAs} to
     * its input, and then applies the {@code after} {@link ResponseAs} to the result.
     */
    default <V> ResponseAs<T, V> andThen(ResponseAs<R, V> after) {
        return new ResponseAs<T, V>() {

            @Override
            public V as(T response) {
                return after.as(ResponseAs.this.as(response));
            }

            @Override
            public boolean aggregationRequired() {
                if (ResponseAs.this.aggregationRequired()) {
                    // The response was aggregated already.
                    return true;
                }
                return after.aggregationRequired();
            }
        };
    }
}

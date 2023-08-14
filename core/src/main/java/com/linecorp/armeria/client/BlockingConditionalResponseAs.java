/*
 * Copyright 2023 LINE Corporation
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

import java.util.function.Predicate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Provides a way for users to add {@link ResponseAs} mappings to transform an aggregated response
 * given that the corresponding {@link Predicate} is satisfied. Note that the conditionals are
 * invoked in the order in which they are added.
 *
 * <pre>{@code
 * RestClient.of(...)
 *   .get("/")
 *   .execute(
 *     ResponseAs.<MyResponse>json(MyMessage.class, res -> res.status().isError())
 *       .andThenJson(EmptyMessage.class, res -> res.status().isInformational())
 *       .orElseJson(MyError.class);
 * }
 */
@UnstableApi
public final class BlockingConditionalResponseAs<V>
        extends DefaultConditionalResponseAs<HttpResponse, AggregatedHttpResponse, ResponseEntity<V>> {
    private static final Predicate<AggregatedHttpResponse> TRUE_PREDICATE = unused -> true;

    BlockingConditionalResponseAs(ResponseAs<HttpResponse, AggregatedHttpResponse> originalResponseAs,
                                  ResponseAs<AggregatedHttpResponse, ResponseEntity<V>> responseAs,
                                  Predicate<AggregatedHttpResponse> predicate) {
        super(originalResponseAs, responseAs, predicate);
    }

    /**
     * Adds a mapping such that the response content will be deserialized
     * to the specified {@link Class} if the {@link Predicate} is satisfied.
     */
    public BlockingConditionalResponseAs<V> andThenJson(
            Class<? extends V> clazz, Predicate<AggregatedHttpResponse> predicate) {
        return andThen(AggregatedResponseAs.json(clazz, predicate), predicate);
    }

    /**
     * Adds a mapping such that the response content will be deserialized
     * to the specified {@link Class} using the {@link ObjectMapper} if the {@link Predicate} is satisfied.
     */
    public BlockingConditionalResponseAs<V> andThenJson(
            Class<? extends V> clazz, ObjectMapper objectMapper, Predicate<AggregatedHttpResponse> predicate) {
        return andThen(AggregatedResponseAs.json(clazz, objectMapper, predicate), predicate);
    }

    /**
     * Adds a mapping such that the response content will be deserialized
     * with the specified {@link TypeReference} if the {@link Predicate} is satisfied.
     */
    public BlockingConditionalResponseAs<V> andThenJson(
            TypeReference<? extends V> typeRef, Predicate<AggregatedHttpResponse> predicate) {
        return andThen(AggregatedResponseAs.json(typeRef, predicate), predicate);
    }

    /**
     * Adds a mapping such that the response content will be deserialized
     * with the specified {@link TypeReference} using the {@link ObjectMapper}
     * if the {@link Predicate} is satisfied.
     */
    public BlockingConditionalResponseAs<V> andThenJson(
            TypeReference<? extends V> typeRef, ObjectMapper objectMapper,
            Predicate<AggregatedHttpResponse> predicate) {
        return andThen(AggregatedResponseAs.json(typeRef, objectMapper, predicate), predicate);
    }

    /**
     * Returns {@link ResponseAs} based on the configured {@link ResponseAs} to {@link Predicate}
     * mappings. If none of the {@link Predicate}s are satisfied, the content will be deserialized
     * to the specified {@link Class}.
     */
    public ResponseAs<HttpResponse, ResponseEntity<V>> orElseJson(Class<? extends V> clazz) {
        return orElse(AggregatedResponseAs.json(clazz, TRUE_PREDICATE));
    }

    /**
     * Returns {@link ResponseAs} based on the configured {@link ResponseAs} to {@link Predicate}
     * mappings. If none of the {@link Predicate}s are satisfied, the content will be deserialized
     * to the specified {@link Class} using the {@link ObjectMapper}.
     */
    public ResponseAs<HttpResponse, ResponseEntity<V>> orElseJson(
            Class<? extends V> clazz, ObjectMapper objectMapper) {
        return orElse(AggregatedResponseAs.json(clazz, objectMapper, TRUE_PREDICATE));
    }

    /**
     * Returns {@link ResponseAs} based on the configured {@link ResponseAs} to {@link Predicate}
     * mappings. If none of the {@link Predicate}s are satisfied, the content will be deserialized
     * using the specified {@link TypeReference}.
     */
    public ResponseAs<HttpResponse, ResponseEntity<V>> orElseJson(TypeReference<? extends V> typeRef) {
        return orElse(AggregatedResponseAs.json(typeRef, TRUE_PREDICATE));
    }

    /**
     * Returns {@link ResponseAs} based on the configured {@link ResponseAs} to {@link Predicate}
     * mappings. If none of the {@link Predicate}s are satisfied, the content will be deserialized
     * using the specified {@link TypeReference} and {@link ObjectMapper}.
     */
    public ResponseAs<HttpResponse, ResponseEntity<V>> orElseJson(
            TypeReference<? extends V> typeRef, ObjectMapper objectMapper) {
        return orElse(AggregatedResponseAs.json(typeRef, objectMapper, TRUE_PREDICATE));
    }

    @Override
    public BlockingConditionalResponseAs<V> andThen(
            ResponseAs<AggregatedHttpResponse, ResponseEntity<V>> responseAs,
            Predicate<AggregatedHttpResponse> predicate) {
        return (BlockingConditionalResponseAs<V>) super.andThen(responseAs, predicate);
    }
}

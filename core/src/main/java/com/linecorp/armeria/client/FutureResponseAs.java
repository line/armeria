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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Asynchronously transforms an {@link HttpResponse} into a {@link ResponseEntity}.
 */
@UnstableApi
@FunctionalInterface
public interface FutureResponseAs<T>
        extends ResponseAs<HttpResponse, CompletableFuture<T>> {

    /**
     * Transforms the {@code T} type object into another by applying the {@link Function}.
     */
    default <U> FutureResponseAs<U> map(Function<T, U> mapper) {
        requireNonNull(mapper, "mapper");
        return response -> as(response).thenApply(mapper);
    }
}

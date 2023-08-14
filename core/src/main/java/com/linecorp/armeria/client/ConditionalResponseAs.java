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

/**
 * Provides a way for users to add {@link ResponseAs} mappings to transform a response
 * given that the corresponding {@link Predicate} is satisfied. Note that the conditionals are
 * invoked in the order in which they are added.
 *
 * <pre>{@code
 * WebClient.of(...)
 *   .prepare()
 *   .get("/server_error")
 *   .as(ResponseAs.blocking()
 *     .<MyResponse>andThen(
 *       res -> new MyError(res.status().codeAsText(), res.contentUtf8()),
 *       res -> res.status().isError())
 *     .andThen(
 *       res -> new EmptyMessage(),
 *       res -> !res.headers().contains("x-header"))
 *     .orElse(res -> new MyMessage(res.contentUtf8())))
 * }
 */
public interface ConditionalResponseAs<T, R, V> {

    /**
     * Adds a mapping such that {@link ResponseAs} will be applied if the {@link Predicate} is satisfied.
     */
    ConditionalResponseAs<T, R, V> andThen(ResponseAs<R, V> responseAs, Predicate<R> predicate);

    /**
     * Returns {@link ResponseAs} based on the configured {@link ResponseAs} to {@link Predicate}
     * mappings. If none of the {@link Predicate}s are satisfied, the specified {@link ResponseAs}
     * is used as a fallback.
     */
    ResponseAs<T, V> orElse(ResponseAs<R, V> responseAs);
}

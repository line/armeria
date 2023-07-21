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
 * Transforms a response into another by given conditions.
 */
public interface ConditionalResponseAs<T, R, V> {

    /**
     * Adds the mapping of {@link ResponseAs} and {@link Predicate} to the List.
     */
    ConditionalResponseAs<T, R, V> andThen(ResponseAs<R, V> responseAs, Predicate<R> predicate);

    /**
     * Returns {@link ResponseAs} whose {@link Predicate} is evaluated as true.
     */
    ResponseAs<T, V> orElse(ResponseAs<R, V> responseAs);
}

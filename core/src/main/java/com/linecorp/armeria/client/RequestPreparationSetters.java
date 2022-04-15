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

import java.time.Duration;

import com.linecorp.armeria.common.HttpRequestSetters;

import io.netty.util.AttributeKey;

interface RequestPreparationSetters<T> extends HttpRequestSetters, RequestOptionsSetters {

    /**
     * Builds and executes the request.
     */
    T execute();

    /**
     * Sets the specified {@link RequestOptions} that could overwrite the previously configured values such as
     * {@link #responseTimeout(Duration)}, {@link #writeTimeout(Duration)}, {@link #maxResponseLength(long)}
     * and {@link #attr(AttributeKey, Object)}.
     */
    RequestPreparationSetters<T> requestOptions(RequestOptions requestOptions);
}

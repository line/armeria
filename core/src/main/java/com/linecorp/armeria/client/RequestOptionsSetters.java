/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

interface RequestOptionsSetters {

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not fully received within
     * the specified {@link Duration} since the {@link Response} started or {@link Request} was fully sent.
     * {@link Duration#ZERO} disables the limit.
     */
    RequestOptionsSetters responseTimeout(Duration responseTimeout);

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not fully received within
     * the specified {@code responseTimeoutMillis} since the {@link Response} started or {@link Request} was
     * fully sent. {@code 0} disables the limit.
     */
    RequestOptionsSetters responseTimeoutMillis(long responseTimeoutMillis);

    /**
     * Sets the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. {@link Duration#ZERO} disables the limit.
     */
    RequestOptionsSetters writeTimeout(Duration writeTimeout);

    /**
     * Sets the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. {@code 0} disables the limit.
     */
    RequestOptionsSetters writeTimeoutMillis(long writeTimeoutMillis);

    /**
     * Sets the maximum allowed length of a server response in bytes.
     * {@code 0} disables the limit.
     */
    RequestOptionsSetters maxResponseLength(long maxResponseLength);

    /**
     * Associates the specified value with the given {@link AttributeKey} in this request.
     * If this context previously contained a mapping for the {@link AttributeKey}, the old value is replaced
     * by the specified value.
     */
    <V> RequestOptionsSetters attr(AttributeKey<V> key, @Nullable V value);
}

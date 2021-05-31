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

import static com.linecorp.armeria.client.DefaultRequestOptions.EMPTY;

import java.util.Iterator;
import java.util.Map.Entry;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * A {@link RequestOptions} for an {@link HttpRequest}.
 */
@UnstableApi
public interface RequestOptions {

    /**
     * Returns an empty {@link RequestOptions}.
     */
    static RequestOptions of() {
        return EMPTY;
    }

    /**
     * Returns a newly created {@link RequestOptionsBuilder}.
     */
    static RequestOptionsBuilder builder() {
        return new RequestOptionsBuilder();
    }

    /**
     * Returns the amount of time allowed until receiving the {@link Response} completely
     * since the transfer of the {@link Response} started or the {@link Request} was fully sent.
     * {@code 0} disables the limit.
     * {@code -1} disables this option and the response timeout of a client will be used instead.
     */
    long responseTimeoutMillis();

    /**
     * Returns the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds.
     * {@code 0} disables the limit.
     * {@code -1} disables this option and the write timeout of a client will be used instead.
     */
    long writeTimeoutMillis();

    /**
     * Returns the maximum length of the received {@link Response}.
     * {@code 0} disables the limit.
     * {@code -1} disables this option and the maximum response length of a client will be used instead.
     */
    long maxResponseLength();

    /**
     * Returns the {@link Iterator} of all {@link Entry}s this {@link RequestOptions} contains.
     */
    Iterator<Entry<AttributeKey<?>, Object>> attrs();
}

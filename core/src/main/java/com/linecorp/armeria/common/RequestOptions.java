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

package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.DefaultRequestOptions.EMPTY;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link RequestOptions} for an {@link HttpRequest}.
 */
@SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
@UnstableApi
public interface RequestOptions {

    /**
     * Returns an empty singleton {@link RequestOptions}.
     */
    static RequestOptions of() {
        return EMPTY;
    }

    /**
     * Returns the response timeout in milliseconds. {@code 0} disables the limit.
     * {@code -1} disables this option and the response timeout of a client is used instead.
     */
    long responseTimeoutMillis();
}

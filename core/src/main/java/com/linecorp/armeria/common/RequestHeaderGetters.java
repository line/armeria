/*
 * Copyright 2019 LINE Corporation
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

import java.net.URI;

import javax.annotation.Nullable;

/**
 * Provides the getter methods to {@link RequestHeaders} and {@link RequestHeadersBuilder}.
 *
 * @see ResponseHeaderGetters
 */
interface RequestHeaderGetters extends HttpHeaderGetters {

    /**
     * Returns the request URI generated from the {@code ":scheme"}, {@code ":authority"} and {@code ":path"}
     * headers.
     *
     * @throws IllegalStateException if any of the required headers do not exist or
     *                               the resulting URI is not valid.
     */
    URI uri();

    /**
     * Returns the value of the {@code ":method"} header as an {@link HttpMethod}.
     * {@link HttpMethod#UNKNOWN} is returned if the value is not defined in {@link HttpMethod}.
     *
     * @throws IllegalStateException if there is no such header.
     */
    HttpMethod method();

    /**
     * Returns the value of the {@code ":path"} header.
     *
     * @throws IllegalStateException if there is no such header.
     */
    String path();

    /**
     * Returns the value of the {@code ":scheme"} header or {@code null} if there is no such header.
     */
    @Nullable
    String scheme();

    /**
     * Returns the value of the {@code ":authority"} header or {@code null} if there is no such header.
     */
    @Nullable
    String authority();
}

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

/**
 * Provides the getter methods to {@link ResponseHeaders} and {@link ResponseHeadersBuilder}.
 *
 * @see RequestHeaderGetters
 */
interface ResponseHeaderGetters extends HttpHeaderGetters {
    /**
     * Returns the value of the {@code ":status"} header as an {@link HttpStatus}.
     * If the value is malformed, {@link HttpStatus#UNKNOWN} will be returned.
     *
     * @throws IllegalStateException if there is no such header.
     */
    HttpStatus status();

    /**
     * Returns the parsed
     * <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-4.1">set-cookie</a> header.
     *
     * @return a {@link Cookies} or an empty {@link Cookies} if there is no such header.
     */
    Cookies cookies();
}

/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.logging;

/**
 * Tells which properties are available in a {@link RequestLog}.
 */
public enum RequestLogAvailability {
    // Request availability
    /**
     * Request processing started. The following properties become available:
     * <ul>
     *   <li>{@link RequestLog#requestStartTimeMillis()}</li>
     *   <li>{@link RequestLog#channel()}</li>
     *   <li>{@link RequestLog#sessionProtocol()}</li>
     *   <li>{@link RequestLog#authority()}</li>
     * </ul>
     */
    REQUEST_START(1, 1),
    /**
     * {@link RequestLog#scheme()} and {@link RequestLog#serializationFormat()} are available,
     * as well as all the properties mentioned in {@link #REQUEST_START}.
     */
    SCHEME(1 | 2, 2),

    /**
     * {@link RequestLog#requestFirstBytesTransferredTimeNanos()} is available, as well as all the
     * properties mentioned in {@link #REQUEST_START}.
     */
    REQUEST_FIRST_BYTES_TRANSFERRED(1 | 4, 4),

    /**
     * {@link RequestLog#requestHeaders()} is available, as well as all the properties mentioned in
     * {@link #REQUEST_START}.
     */
    REQUEST_HEADERS(1 | 8, 8),
    /**
     * {@link RequestLog#requestContent()} is available, as well as all the properties mentioned in
     * {@link #REQUEST_START}.
     */
    REQUEST_CONTENT(1 | 16, 16),
    /**
     * {@link RequestLog#requestLength()}, {@link RequestLog#requestCause()} and
     * {@link RequestLog#requestDurationNanos()} are available, as well as all the properties mentioned in
     * {@link #REQUEST_START}, {@link #SCHEME}, {@link #REQUEST_HEADERS} and {@link #REQUEST_CONTENT}.
     */
    REQUEST_END(1 | 2 | 8 | 16 | 32, 1 | 2 | 8 | 16 | 32),

    // Response availability
    /**
     * {@link RequestLog#responseStartTimeMillis()} is available.
     */
    RESPONSE_START(1 << 16, 1 << 16),

    /**
     * {@link RequestLog#responseFirstBytesTransferredTimeNanos()} is available, as well as all the
     * properties mentioned in {@link #RESPONSE_START}.
     */
    RESPONSE_FIRST_BYTES_TRANSFERRED((1 | 2) << 16, 2 << 16),

    /**
     * {@link RequestLog#responseHeaders()} is available, as well as all the properties mentioned in
     * {@link #RESPONSE_START}.
     */
    RESPONSE_HEADERS((1 | 4) << 16, 4 << 16),
    /**
     * {@link RequestLog#responseContent()} is available, as well as all the properties mentioned in
     * {@link #RESPONSE_START}.
     */
    RESPONSE_CONTENT((1 | 8) << 16, 8 << 16),
    /**
     * {@link RequestLog#responseLength()}, {@link RequestLog#responseCause()},
     * {@link RequestLog#responseDurationNanos()} and {@link RequestLog#totalDurationNanos()} are available,
     * as well as all the properties mentioned in {@link #RESPONSE_START}, {@link #RESPONSE_HEADERS} and
     * {@link #RESPONSE_CONTENT}.
     */
    RESPONSE_END((1 | 4 | 8 | 16) << 16, (1 | 4 | 8 | 16) << 16),

    // Everything
    /**
     * All the properties mentioned in {@link #REQUEST_END} and {@link #RESPONSE_END} are available.
     * Note that {@link #REQUEST_FIRST_BYTES_TRANSFERRED} and {@link #RESPONSE_FIRST_BYTES_TRANSFERRED}
     * may not be fulfilled if network transfer did not occur.
     */
    COMPLETE(1 | 2 | 8 | 16 | 32 | (1 | 4 | 8 | 16) << 16, /* unused */ 0);

    private final int getterFlags;
    private final int setterFlags;

    RequestLogAvailability(int getterFlags, int setterFlags) {
        this.getterFlags = getterFlags;
        this.setterFlags = setterFlags;
    }

    int getterFlags() {
        return getterFlags;
    }

    int setterFlags() {
        return setterFlags;
    }
}

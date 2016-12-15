/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
     * Request processing started, where the following properties become available:
     * <ul>
     *   <li>{@link RequestLog#requestStartTimeMillis()},</li>
     *   <li>{@link RequestLog#channel()},</li>
     *   <li>{@link RequestLog#sessionProtocol()},</li>
     *   <li>{@link RequestLog#host()},</li>
     *   <li>{@link RequestLog#method()},</li>
     *   <li>{@link RequestLog#path()}.</li>
     * </ul>
     */
    REQUEST_START(1, 1),
    /**
     * {@link RequestLog#scheme()} and {@link RequestLog#serializationFormat()} are available,
     * as well as all the properties mentioned in {@link #REQUEST_START}.
     */
    SCHEME(1 | 2, 2),
    /**
     * {@link RequestLog#requestEnvelope()} are available, as well as all the properties mentioned in
     * {@link #REQUEST_START}.
     */
    REQUEST_ENVELOPE(1 | 4, 4),
    /**
     * {@link RequestLog#requestContent()} are available, as well as all the properties mentioned in
     * {@link #REQUEST_START}.
     */
    REQUEST_CONTENT(1 | 8, 8),
    /**
     * {@link RequestLog#requestLength()}, {@link RequestLog#requestCause()} and
     * {@link RequestLog#requestDurationNanos()} are available, as well as all the properties mentioned in
     * {@link #REQUEST_START}, {@link #SCHEME}, {@link #REQUEST_ENVELOPE} and {@link #REQUEST_CONTENT}.
     */
    REQUEST_END(1 | 2 | 4 | 8 | 16, 1 | 2 | 4 | 8 | 16),

    // Response availability
    /**
     * {@link RequestLog#responseStartTimeMillis()} is available.
     */
    RESPONSE_START(1 << 16, 1 << 16),
    /**
     * {@link RequestLog#statusCode()} is available, as well as all the properties mentioned in
     * {@link #RESPONSE_START}.
     */
    STATUS_CODE((1 | 2) << 16, 2 << 16),
    /**
     * {@link RequestLog#responseEnvelope()} is available, as well as all the properties mentioned in
     * {@link #RESPONSE_START}.
     */
    RESPONSE_ENVELOPE((1 | 4) << 16, 4 << 16),
    /**
     * {@link RequestLog#responseContent()} is available, as well as all the properties mentioned in
     * {@link #RESPONSE_START}.
     */
    RESPONSE_CONTENT((1 | 8) << 16, 8 << 16),
    /**
     * {@link RequestLog#responseLength()}, {@link RequestLog#responseCause()},
     * {@link RequestLog#responseDurationNanos()} and {@link RequestLog#totalDurationNanos()} are available,
     * as well as all the properties mentioned in {@link #RESPONSE_START}, {@link #STATUS_CODE},
     * {@link #RESPONSE_ENVELOPE} and {@link #RESPONSE_CONTENT}.
     */
    RESPONSE_END((1 | 2 | 4 | 8 | 16) << 16, (1 | 2 | 4 | 8 | 16) << 16),

    // Everything
    /**
     * All the properties mentioned in {@link #REQUEST_END} and {@link #RESPONSE_END} are available.
     */
    COMPLETE(1 | 2 | 4 | 8 | 16 | (1 | 2 | 4 | 8 | 16) << 16, /* unused */ 0);

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

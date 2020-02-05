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

package com.linecorp.armeria.common.logging;

import static com.google.common.base.Preconditions.checkState;

import java.util.Arrays;
import java.util.Set;

import com.google.common.collect.Sets;

/**
 * A property of {@link RequestLog}, used for identifying properties that have been populated during request
 * processing.
 */
public enum RequestLogProperty {

    // Request properties

    /**
     * {@link RequestLog#requestStartTimeMicros()}, {@link RequestLog#requestStartTimeMillis()},
     * {@link RequestLog#requestStartTimeNanos()}.
     */
    REQUEST_START_TIME(true),

    /**
     * {@link RequestLog#requestEndTimeNanos()}, {@link RequestLog#requestDurationNanos()}.
     */
    REQUEST_END_TIME(true),

    /**
     * {@link RequestLog#requestFirstBytesTransferredTimeNanos()}.
     */
    REQUEST_FIRST_BYTES_TRANSFERRED_TIME(true),

    /**
     * {@link RequestLog#channel()}, {@link RequestLog#sessionProtocol()}, {@link RequestLog#sslSession()},
     * {@link RequestLog#connectionTimings()}.
     */
    SESSION(true),

    /**
     * {@link RequestLog#scheme()}.
     */
    SCHEME(true),

    /**
     * {@link RequestLog#name()}.
     */
    NAME(true),

    /**
     * {@link RequestLog#requestHeaders()}.
     */
    REQUEST_HEADERS(true),

    /**
     * {@link RequestLog#requestContent()}, {@link RequestLog#rawRequestContent()}.
     */
    REQUEST_CONTENT(true),

    /**
     * {@link RequestLog#requestContentPreview()}.
     */
    REQUEST_CONTENT_PREVIEW(true),

    /**
     * {@link RequestLog#requestTrailers()}.
     */
    REQUEST_TRAILERS(true),

    /**
     * {@link RequestLog#requestLength()}.
     */
    REQUEST_LENGTH(true),

    /**
     * {@link RequestLog#requestCause()}.
     */
    REQUEST_CAUSE(true),

    // Response properties

    /**
     * {@link RequestLog#responseStartTimeMicros()}, {@link RequestLog#responseStartTimeMillis()},
     * {@link RequestLog#responseStartTimeNanos()}.
     */
    RESPONSE_START_TIME(false),

    /**
     * {@link RequestLog#responseEndTimeNanos()}, {@link RequestLog#responseDurationNanos()},
     * {@link RequestLog#totalDurationNanos()}.
     */
    RESPONSE_END_TIME(false),

    /**
     * {@link RequestLog#responseFirstBytesTransferredTimeNanos()}.
     */
    RESPONSE_FIRST_BYTES_TRANSFERRED_TIME(false),

    /**
     * {@link RequestLog#responseHeaders()}.
     */
    RESPONSE_HEADERS(false),

    /**
     * {@link RequestLog#responseContent()}.
     */
    RESPONSE_CONTENT(false),

    /**
     * {@link RequestLog#responseContentPreview()}.
     */
    RESPONSE_CONTENT_PREVIEW(false),

    /**
     * {@link RequestLog#responseTrailers()}.
     */
    RESPONSE_TRAILERS(false),

    /**
     * {@link RequestLog#responseLength()}.
     */
    RESPONSE_LENGTH(false),

    /**
     * {@link RequestLog#responseCause()}.
     */
    RESPONSE_CAUSE(false);

    private static final Set<RequestLogProperty> REQUEST_PROPERTIES =
            Arrays.stream(values())
                  .filter(p -> p.isRequestProperty)
                  .collect(Sets.toImmutableEnumSet());

    private static final Set<RequestLogProperty> RESPONSE_PROPERTIES =
            Arrays.stream(values())
                  .filter(p -> !p.isRequestProperty)
                  .collect(Sets.toImmutableEnumSet());

    static final int FLAGS_REQUEST_COMPLETE;
    static final int FLAGS_RESPONSE_COMPLETE;
    static final int FLAGS_ALL_COMPLETE;

    static {
        FLAGS_REQUEST_COMPLETE = flags(REQUEST_PROPERTIES);
        FLAGS_RESPONSE_COMPLETE = flags(RESPONSE_PROPERTIES);
        FLAGS_ALL_COMPLETE = FLAGS_REQUEST_COMPLETE | FLAGS_RESPONSE_COMPLETE;
    }

    /**
     * Returns the {@link RequestLogProperty}s for requests.
     */
    public static Set<RequestLogProperty> requestProperties() {
        return REQUEST_PROPERTIES;
    }

    /**
     * Returns the {@link RequestLogProperty}s for responses.
     */
    public static Set<RequestLogProperty> responseProperties() {
        return RESPONSE_PROPERTIES;
    }

    static int flags(RequestLogProperty... properties) {
        int flags = 0;
        for (RequestLogProperty property : properties) {
            flags |= property.flag();
        }
        return flags;
    }

    static int flags(Iterable<RequestLogProperty> properties) {
        int flags = 0;
        for (RequestLogProperty property : properties) {
            flags |= property.flag();
        }
        return flags;
    }

    private final int flag;
    private final boolean isRequestProperty;

    RequestLogProperty(boolean isRequestProperty) {
        // We only allow 31 properties so negative flags can be used as a default value.
        checkState(ordinal() <= 30,
                   "More than 31 properties defined. " +
                   "Please switch all 'flags' to long and update this check to 62.");
        flag = 1 << ordinal();
        this.isRequestProperty = isRequestProperty;
    }

    int flag() {
        return flag;
    }
}

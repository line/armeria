/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.ADDITIONAL_REQUEST_HEADER_DISALLOWED_LIST;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isTrailerDisallowed;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.internal.client.HttpHeaderUtil;
import com.linecorp.armeria.internal.common.util.HttpTimestampSupplier;

import io.netty.util.AsciiString;

// TODO(minwoox): Replace this class with CompositeHeaders.
public final class HttpHeadersUtil {

    /**
     * Merges the given {@link ResponseHeaders}. The headers have priority in the following order.
     * <pre>{@code
     * additional headers (highest priority) > headers > default headers > framework headers (lowest priority)
     * }</pre>
     */
    public static ResponseHeaders mergeResponseHeaders(ResponseHeaders headers,
                                                       HttpHeaders additionalHeaders,
                                                       HttpHeaders defaultHeaders,
                                                       boolean serverHeaderEnabled, boolean dateHeaderEnabled) {
        if (additionalHeaders.isEmpty() && defaultHeaders.isEmpty() &&
            !serverHeaderEnabled && !dateHeaderEnabled) {
            return headers;
        }

        final ResponseHeadersBuilder builder = headers.toBuilder();

        for (AsciiString name : additionalHeaders.names()) {
            if (!isPseudoHeader(name)) {
                builder.remove(name);
                additionalHeaders.forEachValue(name, value -> builder.add(name, value));
            }
        }

        for (AsciiString name : defaultHeaders.names()) {
            if (!isPseudoHeader(name) && !builder.contains(name)) {
                defaultHeaders.forEachValue(name, value -> builder.add(name, value));
            }
        }

        // Framework headers
        if (serverHeaderEnabled && !builder.contains(HttpHeaderNames.SERVER)) {
            builder.add(HttpHeaderNames.SERVER, ArmeriaHttpUtil.SERVER_HEADER);
        }
        if (dateHeaderEnabled && !builder.contains(HttpHeaderNames.DATE)) {
            builder.add(HttpHeaderNames.DATE, HttpTimestampSupplier.currentTime());
        }

        return builder.build();
    }

    /**
     * Merges the given {@link RequestHeaders}. The headers have priority in the following order.
     * <pre>{@code
     * additional headers (highest priority) > headers > default headers > framework headers (lowest priority)
     * }</pre>
     */
    public static RequestHeaders mergeRequestHeaders(RequestHeaders headers,
                                                     HttpHeaders defaultHeaders,
                                                     HttpHeaders additionalHeaders) {
        if (defaultHeaders.isEmpty() && additionalHeaders.isEmpty() &&
            headers.contains(HttpHeaderNames.USER_AGENT)) {
            return headers;
        }

        final RequestHeadersBuilder builder = headers.toBuilder();

        for (AsciiString name : additionalHeaders.names()) {
            if (name.equals(HttpHeaderNames.AUTHORITY) || name.equals(HttpHeaderNames.HOST)) {
                builder.authority(additionalHeaders.get(name));
            } else if (!ADDITIONAL_REQUEST_HEADER_DISALLOWED_LIST.contains(name)) {
                builder.remove(name);
                additionalHeaders.forEachValue(name, value -> builder.add(name, value));
            }
        }

        for (AsciiString name : defaultHeaders.names()) {
            if (name.equals(HttpHeaderNames.AUTHORITY) || name.equals(HttpHeaderNames.HOST)) {
                if (builder.authority() == null) {
                    builder.authority(defaultHeaders.get(name));
                }
            } else if (!ADDITIONAL_REQUEST_HEADER_DISALLOWED_LIST.contains(name) && !builder.contains(name)) {
                defaultHeaders.forEachValue(name, value -> builder.add(name, value));
            }
        }

        // Framework headers
        if (!builder.contains(HttpHeaderNames.USER_AGENT)) {
            builder.add(HttpHeaderNames.USER_AGENT, HttpHeaderUtil.USER_AGENT.toString());
        }

        return builder.build();
    }

    public static HttpHeaders mergeTrailers(HttpHeaders headers, HttpHeaders additionalTrailers) {
        if (additionalTrailers.isEmpty()) {
            return headers;
        }
        if (headers.isEmpty()) {
            return additionalTrailers;
        }

        final HttpHeadersBuilder builder = headers.toBuilder();
        for (AsciiString name : additionalTrailers.names()) {
            //  Pseudo-headers are disallowed for additional trailers
            if (!isPseudoHeader(name) &&
                !isTrailerDisallowed(name)) {
                builder.remove(name);
                additionalTrailers.forEachValue(name, value -> builder.add(name, value));
            }
        }
        return builder.build();
    }

    /**
     * Returns whether the specified header name is a pseudo header.
     */
    public static boolean isPseudoHeader(AsciiString name) {
        return !name.isEmpty() && name.charAt(0) == ':';
    }

    private HttpHeadersUtil() {}
}

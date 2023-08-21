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
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.util.HttpTimestampSupplier;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AsciiString;

// TODO(minwoox): Replace this class with CompositeHeaders.
public final class HttpHeadersUtil {

    public static final String CLOSE_STRING = HttpHeaderValues.CLOSE.toString();

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
                                                     HttpHeaders additionalHeaders,
                                                     HttpHeaders internalHeaders) {
        if (defaultHeaders.isEmpty() && additionalHeaders.isEmpty() && internalHeaders.isEmpty() &&
            headers.contains(HttpHeaderNames.USER_AGENT)) {
            return headers;
        }
        if (defaultHeaders.isEmpty() && additionalHeaders.isEmpty()) {
            boolean containAllInternalHeaders = true;
            for (AsciiString name : internalHeaders.names()) {
                if (!headers.contains(name)) {
                    containAllInternalHeaders = false;
                    break;
                }
            }

            if (containAllInternalHeaders) {
                return headers;
            }
        }

        final RequestHeadersBuilder builder = headers.toBuilder();

        // Additional headers
        String authority = additionalHeaders.get(HttpHeaderNames.AUTHORITY);
        if (authority == null) {
            authority = additionalHeaders.get(HttpHeaderNames.HOST);
        }
        if (authority != null) {
            builder.authority(authority);
        }

        for (AsciiString name : additionalHeaders.names()) {
            if (name.equals(HttpHeaderNames.AUTHORITY) || name.equals(HttpHeaderNames.HOST)) {
                continue; // Manually handled above.
            } else if (!ADDITIONAL_REQUEST_HEADER_DISALLOWED_LIST.contains(name)) {
                builder.remove(name);
                additionalHeaders.forEachValue(name, value -> builder.add(name, value));
            }
        }

        // Default headers
        if (builder.authority() == null) {
            String authority0 = defaultHeaders.get(HttpHeaderNames.AUTHORITY);
            if (authority0 == null) {
                authority0 = defaultHeaders.get(HttpHeaderNames.HOST);
            }
            if (authority0 != null) {
                builder.authority(authority0);
            }
        }

        for (AsciiString name : defaultHeaders.names()) {
            if (name.equals(HttpHeaderNames.AUTHORITY) || name.equals(HttpHeaderNames.HOST)) {
                continue; // Manually handled above.
            } else if (!ADDITIONAL_REQUEST_HEADER_DISALLOWED_LIST.contains(name) && !builder.contains(name)) {
                defaultHeaders.forEachValue(name, value -> builder.add(name, value));
            }
        }

        // Internal headers
        if (builder.authority() == null) {
            String authority0 = internalHeaders.get(HttpHeaderNames.AUTHORITY);
            if (authority0 == null) {
                authority0 = internalHeaders.get(HttpHeaderNames.HOST);
            }
            if (authority0 != null) {
                builder.authority(authority0);
            }
        }

        for (AsciiString name : internalHeaders.names()) {
            if (name.equals(HttpHeaderNames.AUTHORITY) || name.equals(HttpHeaderNames.HOST)) {
                continue; // Manually handled above.
            } else if (!ADDITIONAL_REQUEST_HEADER_DISALLOWED_LIST.contains(name) && !builder.contains(name)) {
                internalHeaders.forEachValue(name, value -> builder.add(name, value));
            }
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

    public static String getScheme(SessionProtocol sessionProtocol) {
        if (sessionProtocol.isHttps()) {
            return "https";
        } else if (sessionProtocol.isHttp()) {
            return "http";
        } else {
            throw new IllegalArgumentException("sessionProtocol: " + sessionProtocol +
                                               " (expected: HTTPS, H2, H1, HTTP, H2C or H1C)");
        }
    }

    /**
     * Returns whether the specified header name is a pseudo header.
     */
    public static boolean isPseudoHeader(AsciiString name) {
        return !name.isEmpty() && name.charAt(0) == ':';
    }

    private HttpHeadersUtil() {}
}

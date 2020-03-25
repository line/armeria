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

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.ADDITIONAL_REQUEST_HEADER_BLACKLIST;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.ADDITIONAL_RESPONSE_HEADER_BLACKLIST;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isTrailerBlacklisted;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;

import io.netty.util.AsciiString;

// TODO(minwoox): Replace this class with CompositeHeaders.
public final class HttpHeadersUtil {

    public static ResponseHeaders mergeResponseHeaders(ResponseHeaders headers,
                                                       HttpHeaders additionalHeaders) {
        if (additionalHeaders.isEmpty()) {
            return headers;
        }

        final ResponseHeadersBuilder builder = headers.toBuilder();
        for (AsciiString name : additionalHeaders.names()) {
            if (!ADDITIONAL_RESPONSE_HEADER_BLACKLIST.contains(name)) {
                builder.remove(name);
                additionalHeaders.forEachValue(name, value -> builder.add(name, value));
            }
        }
        return builder.build();
    }

    public static RequestHeaders mergeRequestHeaders(RequestHeaders headers,
                                                     HttpHeaders additionalHeaders) {
        if (additionalHeaders.isEmpty()) {
            return headers;
        }

        final RequestHeadersBuilder builder = headers.toBuilder();
        for (AsciiString name : additionalHeaders.names()) {
            if (!ADDITIONAL_REQUEST_HEADER_BLACKLIST.contains(name)) {
                builder.remove(name);
                additionalHeaders.forEachValue(name, value -> builder.add(name, value));
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
            if (!ADDITIONAL_RESPONSE_HEADER_BLACKLIST.contains(name) &&
                !isTrailerBlacklisted(name)) {
                builder.remove(name);
                additionalTrailers.forEachValue(name, value -> builder.add(name, value));
            }
        }
        return builder.build();
    }

    private HttpHeadersUtil() {}
}

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

package com.linecorp.armeria.common;

import static com.google.common.base.MoreObjects.firstNonNull;

import io.netty.util.AsciiString;

/**
 * Converts a normalized HTTP/2 header name to another HTTP/1 header name.
 */
@FunctionalInterface
public interface Http1HeaderNaming {

    /**
     * Returns the default {@link Http1HeaderNaming}.
     */
    static Http1HeaderNaming ofDefault() {
        return AsciiString::toString;
    }

    /**
     * Converts lower-cased HTTP/2 header names to the traditional HTTP/1 header names which are defined at
     * {@link HttpHeaderNames}. For example, {@code "user-agent"} is converted to {@code "User-Agent"}.
     * Note that a header name which is not defined at {@link HttpHeaderNames} will be sent in lower-case.
     */
    static Http1HeaderNaming traditional() {
        return headerName -> {
            final String originalHeaderName = HttpHeaderNames.rawHeaderName(headerName);
            return firstNonNull(originalHeaderName, headerName.toString());
        };
    }

    /**
     * Converts the specified HTTP/2 {@linkplain AsciiString headerName} into another HTTP/1 header name.
     */
    String convert(AsciiString http2HeaderName);
}

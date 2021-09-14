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

package com.linecorp.armeria.server.resteasy;

import javax.ws.rs.core.NewCookie;

import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.internal.common.resteasy.CookieConverter;

/**
 * Utility class to handle "Set-Cookie" header values.
 */
final class SetCookie {

    public static SetCookie of(NewCookie cookie) {
        return new SetCookie(cookie);
    }

    private final CookieConverter converter;

    private SetCookie(NewCookie cookie) {
        converter = new CookieConverter(cookie);
    }

    /**
     * Encodes this cookie into a single {@code "Set-Cookie"} header value.
     * @param strict whether to validate that the cookie name and value are in the valid scope
     *               defined in RFC 6265.
     * @return a single {@code "Set-Cookie"} header value.
     */
    public String toHeaderValue(boolean strict) {
        return converter.toSetCookieHeader(strict);
    }

    /**
     * Encodes this cookie into a single {@code "Set-Cookie"} header value.
     * @return a single {@code "Set-Cookie"} header value.
     */
    public String toHeaderValue() {
        return converter.toSetCookieHeader();
    }

    public void addHeader(HttpHeadersBuilder headersBuilder, boolean strict) {
        headersBuilder.add(converter.headerName(), toHeaderValue(strict));
    }

    public void addHeader(HttpHeadersBuilder headersBuilder) {
        headersBuilder.add(converter.headerName(), toHeaderValue());
    }
}

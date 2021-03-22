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

import javax.annotation.Nullable;

@SuppressWarnings({ "checkstyle:EqualsHashCode", "EqualsAndHashcode" })
final class DefaultResponseHeaders extends DefaultHttpHeaders implements ResponseHeaders {

    @Nullable
    private HttpStatus status;
    @Nullable
    private Cookies cookies;

    DefaultResponseHeaders(HttpHeadersBase headers) {
        super(headers);
    }

    DefaultResponseHeaders(HttpHeaderGetters headers) {
        super(headers);
    }

    @Override
    public HttpStatus status() {
        final HttpStatus status = this.status;
        if (status != null) {
            return status;
        }

        return this.status = super.status();
    }

    @Override
    public Cookies cookies() {
        final Cookies cookies = this.cookies;
        if (cookies != null) {
            return cookies;
        }
        return this.cookies = super.setCookie();
    }

    @Override
    public ResponseHeadersBuilder toBuilder() {
        return new DefaultResponseHeadersBuilder(this);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof ResponseHeaders && super.equals(o);
    }
}

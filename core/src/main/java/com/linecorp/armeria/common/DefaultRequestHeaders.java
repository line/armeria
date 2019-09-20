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

import java.net.URI;

import javax.annotation.Nullable;

@SuppressWarnings("EqualsAndHashcode")
final class DefaultRequestHeaders extends DefaultHttpHeaders implements RequestHeaders {

    @Nullable
    private HttpMethod method;
    @Nullable
    private URI uri;

    DefaultRequestHeaders(HttpHeadersBase headers) {
        super(headers);
    }

    DefaultRequestHeaders(HttpHeaderGetters headers) {
        super(headers);
    }

    @Override
    public URI uri() {
        final URI uri = this.uri;
        if (uri != null) {
            return uri;
        }

        return this.uri = super.uri();
    }

    @Override
    public HttpMethod method() {
        final HttpMethod method = this.method;
        if (method != null) {
            return method;
        }

        return this.method = super.method();
    }

    @Override
    public String path() {
        return super.path();
    }

    @Nullable
    @Override
    public String scheme() {
        return super.scheme();
    }

    @Nullable
    @Override
    public String authority() {
        return super.authority();
    }

    @Override
    public RequestHeadersBuilder toBuilder() {
        return new DefaultRequestHeadersBuilder(this);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RequestHeaders && super.equals(o);
    }
}

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

import static com.google.common.base.Preconditions.checkState;

final class DefaultResponseHeadersBuilder
        extends AbstractHttpHeadersBuilder<ResponseHeadersBuilder>
        implements ResponseHeadersBuilder {

    private static final String STATUS_HEADER_MISSING = ":status header does not exist.";

    DefaultResponseHeadersBuilder() {}

    DefaultResponseHeadersBuilder(DefaultResponseHeaders headers) {
        super(headers);
    }

    @Override
    public ResponseHeaders build() {
        final HttpHeadersBase delegate = delegate();
        if (delegate != null) {
            checkState(delegate.contains(HttpHeaderNames.STATUS), STATUS_HEADER_MISSING);
            return new DefaultResponseHeaders(promoteDelegate());
        }

        final HttpHeadersBase parent = parent();
        if (parent != null) {
            if (parent instanceof ResponseHeaders) {
                return (ResponseHeaders) parent;
            } else {
                return updateParent(new DefaultResponseHeaders(parent));
            }
        }

        // No headers were set.
        throw new IllegalStateException(STATUS_HEADER_MISSING);
    }

    @Override
    public HttpStatus status() {
        final HttpHeadersBase getters = getters();
        checkState(getters != null, ":status header does not exist.");
        return getters.status();
    }

    @Override
    public Cookies cookies() {
        final HttpHeadersBase getters = getters();
        checkState(getters != null, ":set-cookie headers does not exist.");
        return getters.setCookies();
    }

    @Override
    public ResponseHeadersBuilder status(int statusCode) {
        setters().status(statusCode);
        return this;
    }

    @Override
    public ResponseHeadersBuilder status(HttpStatus status) {
        setters().status(status);
        return this;
    }
}

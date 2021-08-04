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

@SuppressWarnings("checkstyle:EqualsHashCode")
class DefaultHttpHeaders extends HttpHeadersBase implements HttpHeaders {

    static final DefaultHttpHeaders EMPTY = new DefaultHttpHeaders(false);

    static final DefaultHttpHeaders EMPTY_EOS = new DefaultHttpHeaders(true);

    /**
     * Creates an empty headers.
     */
    private DefaultHttpHeaders(boolean endOfStream) {
        // Note that we do not specify a small size hint here, because a user may create a new builder
        // derived from an empty headers and add many headers. If we specified a small hint, such a headers
        // would suffer from hash collisions.
        super(DEFAULT_SIZE_HINT);
        endOfStream(endOfStream);
    }

    /**
     * Creates a shallow copy of the specified {@link HttpHeadersBase}.
     */
    DefaultHttpHeaders(HttpHeadersBase headers) {
        super(headers, true);
    }

    /**
     * Creates a deep copy of the specified {@link HttpHeaderGetters}.
     */
    DefaultHttpHeaders(HttpHeaderGetters headers) {
        super(headers);
    }

    @Nullable
    @Override
    public final MediaType contentType() {
        return super.contentType();
    }

    @Nullable
    @Override
    public final ContentDisposition contentDisposition() {
        return super.contentDisposition();
    }

    @Override
    public HttpHeadersBuilder toBuilder() {
        return new DefaultHttpHeadersBuilder(this);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof HttpHeaders && super.equals(o);
    }
}

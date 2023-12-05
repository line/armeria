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

final class DefaultHttpHeadersBuilder
        extends AbstractHttpHeadersBuilder<DefaultHttpHeadersBuilder>
        implements HttpHeadersBuilder {

    DefaultHttpHeadersBuilder() {}

    DefaultHttpHeadersBuilder(HttpHeadersBase parent) {
        super(parent);
    }

    @Override
    public HttpHeaders build() {
        final HttpHeadersBase delegate = delegate();
        if (delegate != null) {
            // The size could be empty although delegate.isContentLengthUnknown() returns true.
            if (delegate.isEmpty() && !delegate.isContentLengthUnknown()) {
                return delegate.isEndOfStream() ? DefaultHttpHeaders.EMPTY_EOS : DefaultHttpHeaders.EMPTY;
            } else {
                return new DefaultHttpHeaders(promoteDelegate());
            }
        }

        final HttpHeadersBase parent = parent();
        if (parent != null) {
            if (parent instanceof HttpHeaders) {
                return (HttpHeaders) parent;
            }
            return updateParent(new DefaultHttpHeaders(parent));
        }

        return DefaultHttpHeaders.EMPTY;
    }
}

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

import io.netty.util.AsciiString;

abstract class AbstractHttpHeadersBuilder<SELF extends HttpHeadersBuilder> extends StringMultimapBuilder<
        /* IN_NAME */ CharSequence, /* NAME */ AsciiString, /* CONTAINER */ HttpHeadersBase, SELF> {

    AbstractHttpHeadersBuilder() {}

    AbstractHttpHeadersBuilder(HttpHeadersBase parent) {
        super(parent);
        assert parent instanceof HttpHeaders;
    }

    @Override
    final HttpHeadersBase newSetters(int sizeHint) {
        return new HttpHeadersBase(sizeHint);
    }

    @Override
    final HttpHeadersBase newSetters(HttpHeadersBase parent, boolean shallowCopy) {
        return new HttpHeadersBase(parent, shallowCopy);
    }

    // Shortcuts

    @Nullable
    public final MediaType contentType() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.contentType() : null;
    }

    public final SELF contentType(MediaType contentType) {
        setters().contentType(contentType);
        return self();
    }

    // Getters

    public final boolean isEndOfStream() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.isEndOfStream() : false;
    }

    // Setters

    public final SELF endOfStream(boolean endOfStream) {
        setters().endOfStream(endOfStream);
        return self();
    }
}

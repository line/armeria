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
/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;

import io.netty.util.AsciiString;

final class DefaultBodyPartHeaders implements BodyPartHeaders {

    // Forked from https://github.com/oracle/helidon/blob/ab23ce10cb55043e5e4beea1037a65bb8968354b/media/multipart/src/main/java/io/helidon/media/multipart/BodyPartHeaders.java#

    static final DefaultBodyPartHeaders EMPTY = new DefaultBodyPartHeaders(HttpHeaders.of());

    private final HttpHeaders params;

    @Nullable
    private MediaType contentType;
    @Nullable
    private ContentDisposition contentDisposition;

    DefaultBodyPartHeaders(HttpHeaders params) {
        this.params = params;
    }

    @Override
    @Nullable
    public MediaType contentType() {
        if (contentType != null) {
            return contentType;
        }

        final String rawContentType = get(HttpHeaderNames.CONTENT_TYPE);
        if (rawContentType != null) {
            return contentType = MediaType.parse(rawContentType);
        } else {
            return null;
        }
    }

    @Override
    @Nullable
    public ContentDisposition contentDisposition() {
        if (contentDisposition != null) {
            return contentDisposition;
        }

        final String rawContentDisposition = get(HttpHeaderNames.CONTENT_DISPOSITION);
        if (rawContentDisposition != null) {
            return contentDisposition = ContentDisposition.parse(rawContentDisposition);
        } else {
            return null;
        }
    }

    @Override
    @Nullable
    public String get(final CharSequence name) {
        return params.get(name);
    }

    @Override
    public List<String> getAll(final CharSequence name) {
        return params.getAll(name);
    }

    @Override
    public Iterator<Entry<AsciiString, String>> iterator() {
        return params.iterator();
    }

    @Override
    public String toString() {
        return params.toString();
    }
}

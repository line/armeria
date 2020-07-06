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
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.MediaType;

/**
 * A builder class for creating {@link BodyPartHeaders}.
 */
public final class BodyPartHeadersBuilder {

    private final HttpHeadersBuilder headers = HttpHeaders.builder();

    @Nullable
    private ContentDisposition contentDisposition;

    private boolean isContentTypeSet;

    BodyPartHeadersBuilder() {}

    /**
     * Adds a new header with the specified {@code name} and {@code value}.
     */
    public BodyPartHeadersBuilder add(String name, String value) {
        return header(name, value, true);
    }

    /**
     * Sets a header with the specified name and value. Any existing headers with the same name are
     * overwritten.
     */
    public BodyPartHeadersBuilder set(CharSequence name, String value) {
        return header(name, value, false);
    }

    private BodyPartHeadersBuilder header(CharSequence name, String value, boolean add) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        if (HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(name)) {
            isContentTypeSet = true;
        }
        if (HttpHeaderNames.CONTENT_DISPOSITION.contentEqualsIgnoreCase(name)) {
            contentDisposition = ContentDisposition.parse(value);
        }

        if (add) {
            headers.add(name, value);
        } else {
            headers.set(name, value);
        }
        return this;
    }

    /**
     * Adds the specified {@code Content-Type} header.
     */
    public BodyPartHeadersBuilder contentType(MediaType contentType) {
        requireNonNull(contentType, "contentType");
        isContentTypeSet = true;
        return add(HttpHeaderNames.CONTENT_TYPE.toString(), contentType.toString());
    }

    /**
     * Adds the specified {@code Content-Disposition} header.
     */
    public BodyPartHeadersBuilder contentDisposition(ContentDisposition contentDisposition) {
        requireNonNull(contentDisposition, "contentDisposition");
        this.contentDisposition = contentDisposition;
        return add(HttpHeaderNames.CONTENT_DISPOSITION.toString(), contentDisposition.toString());
    }

    /**
     * Returns a newly created {@link BodyPartHeaders} with the entries in this builder.
     */
    public BodyPartHeaders build() {
        if (!isContentTypeSet) {
            contentType(defaultContentType());
        }
        return new DefaultBodyPartHeaders(headers.build());
    }

    /**
     * Returns the default {@code Content-Type} header value:
     * {@link MediaType#OCTET_STREAM} if the
     * {@code Content-Disposition} header is present with a non empty value,
     * otherwise {@link MediaType#PLAIN_TEXT}.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7578#section-4.4">RFC-7578</a>
     */
    private MediaType defaultContentType() {
        if (contentDisposition != null && contentDisposition.filename() != null) {
            return MediaType.OCTET_STREAM;
        } else {
            return MediaType.PLAIN_TEXT;
        }
    }
}

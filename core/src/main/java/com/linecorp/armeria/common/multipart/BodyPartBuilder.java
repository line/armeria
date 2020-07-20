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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;

/**
 * A builder class for creating {@link BodyPart} instances.
 */
public final class BodyPartBuilder {

    private static final Multi<HttpData> EMPTY = Multi.empty();

    private HttpHeaders headers = HttpHeaders.of();
    private Multi<HttpData> content = EMPTY;

    BodyPartBuilder() {}

    /**
     * Sets the specified headers for this part.
     * @param headers headers
     */
    public BodyPartBuilder headers(HttpHeaders headers) {
        requireNonNull(headers, "headers");
        this.headers = headers;
        return this;
    }

    // TODO(ikhoon): Add builder methods for `File` and `Path` contents

    /**
     * Adds a new body part backed by the specified {@link Publisher}.
     * @param publisher publisher for the part content
     */
    public BodyPartBuilder content(Publisher<? extends HttpData> publisher) {
        requireNonNull(publisher, "publisher");
        if (content == EMPTY) {
            content = Multi.from(publisher);
        } else {
            content = Multi.concat(content, publisher);
        }
        return this;
    }

    /**
     * Adds the specified {@link CharSequence} as a body part content.
     */
    public BodyPartBuilder content(CharSequence content) {
        requireNonNull(content, "content");
        return content(HttpData.ofUtf8(content));
    }

    /**
     * Adds the specified {@code bytes} as a body part content.
     */
    public BodyPartBuilder content(byte[] contents) {
        requireNonNull(contents, "contents");
        return content(HttpData.copyOf(contents));
    }

    /**
     * Adds the specified {@link HttpData} as a body part content.
     */
    public BodyPartBuilder content(HttpData content) {
        requireNonNull(content, "content");
        return content(Multi.singleton(content));
    }

    /**
     * Returns the default {@code "content-type"} header value:
     * {@link MediaType#OCTET_STREAM} if the {@code "content-disposition"} header is present with
     * a non empty value, otherwise {@link MediaType#PLAIN_TEXT}.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7578#section-4.4">RFC-7578</a>
     */
    private static MediaType defaultContentType(@Nullable ContentDisposition contentDisposition) {
        if (contentDisposition != null && contentDisposition.filename() != null) {
            return MediaType.OCTET_STREAM;
        } else {
            return MediaType.PLAIN_TEXT;
        }
    }

    /**
     * Returns a newly-created {@link BodyPart}.
     */
    public BodyPart build() {
        checkState(content != EMPTY, "Should set at least one content");
        final HttpHeaders headers;
        if (this.headers.contentType() == null) {
            final MediaType defaultContentType = defaultContentType(this.headers.contentDisposition());
            headers = this.headers.withMutations(builder -> builder.contentType(defaultContentType));
        } else {
            headers = this.headers;
        }
        return new DefaultBodyPart(headers, content);
    }
}

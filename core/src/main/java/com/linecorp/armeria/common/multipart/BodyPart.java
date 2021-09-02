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
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * A body part entity.
 */
public interface BodyPart {

    /**
     * Returns a new UTF-8-encoded {@link BodyPart} using the specified {@link ContentDisposition} and
     * {@link CharSequence}.
     */
    static BodyPart of(ContentDisposition contentDisposition, CharSequence content) {
        requireNonNull(content, "content");
        return of(contentDisposition, HttpData.ofUtf8(content));
    }

    /**
     * Returns a new UTF-8-encoded {@link BodyPart} using the specified {@link ContentDisposition},
     * {@link MediaType} and {@link CharSequence}.
     */
    static BodyPart of(ContentDisposition contentDisposition, MediaType contentType, CharSequence content) {
        requireNonNull(content, "content");
        return of(contentDisposition, contentType, HttpData.ofUtf8(content));
    }

    /**
     * Returns a new UTF-8-encoded {@link BodyPart} using the specified {@link HttpHeaders} and
     * {@link CharSequence}.
     */
    static BodyPart of(HttpHeaders headers, CharSequence content) {
        requireNonNull(content, "content");
        return of(headers, HttpData.ofUtf8(content));
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link ContentDisposition} and
     * {@link HttpData}.
     */
    static BodyPart of(ContentDisposition contentDisposition, HttpData content) {
        requireNonNull(content, "content");
        return of(contentDisposition, StreamMessage.of(content));
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link ContentDisposition}, {@link MediaType} and
     * {@link HttpData}.
     */
    static BodyPart of(ContentDisposition contentDisposition, MediaType contentType, HttpData content) {
        requireNonNull(content, "content");
        return of(contentDisposition, contentType, StreamMessage.of(content));
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link HttpHeaders} and
     * {@code bytes}.
     */
    static BodyPart of(HttpHeaders headers, HttpData content) {
        requireNonNull(content, "content");
        return of(headers, StreamMessage.of(content));
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link ContentDisposition} and
     * {@link Publisher} of {@link HttpData}.
     */
    static BodyPart of(ContentDisposition contentDisposition, Publisher<? extends HttpData> publisher) {
        final HttpHeaders headers = HttpHeaders.builder().contentDisposition(contentDisposition).build();
        return of(headers, publisher);
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link ContentDisposition}, {@link MediaType} and
     * {@link Publisher} of {@link HttpData}.
     */
    static BodyPart of(ContentDisposition contentDisposition, MediaType contentType,
                       Publisher<? extends HttpData> publisher) {
        requireNonNull(contentDisposition, "contentDisposition");
        requireNonNull(contentType, "contentType");
        requireNonNull(publisher, "publisher");
        final HttpHeaders headers = HttpHeaders.builder()
                                               .contentDisposition(contentDisposition)
                                               .contentType(contentType)
                                               .build();
        return of(headers, publisher);
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link HttpHeaders} and
     * {@link Publisher} of {@link HttpData}.
     */
    static BodyPart of(HttpHeaders headers, Publisher<? extends HttpData> publisher) {
        return builder().headers(headers).content(publisher).build();
    }

    /**
     * Returns a new {@link BodyPartBuilder}.
     */
    static BodyPartBuilder builder() {
        return new BodyPartBuilder();
    }

    /**
     * Returns HTTP part headers.
     */
    HttpHeaders headers();

    /**
     * Returns the reactive representation of the part content.
     */
    @CheckReturnValue
    StreamMessage<HttpData> content();

    /**
     * Returns the control name.
     *
     * @return the {@code name} parameter of the {@code "content-disposition"}
     *         header, or {@code null} if not present.
     */
    @Nullable
    default String name() {
        @Nullable
        final ContentDisposition contentDisposition = headers().contentDisposition();
        if (contentDisposition != null) {
            return contentDisposition.name();
        } else {
            return null;
        }
    }

    /**
     * Returns the file name.
     *
     * @return the {@code filename} parameter of the {@code "content-disposition"}
     *         header, or {@code null} if not present.
     */
    @Nullable
    default String filename() {
        @Nullable
        final ContentDisposition contentDisposition = headers().contentDisposition();
        if (contentDisposition != null) {
            return contentDisposition.filename();
        } else {
            return null;
        }
    }
}

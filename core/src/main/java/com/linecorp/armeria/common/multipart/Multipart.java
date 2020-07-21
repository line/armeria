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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.multipart.DefaultMultipart.DEFAULT_BOUNDARY;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Publisher;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.SubscriptionOption;

/**
 * A reactive {@link Multipart} that represents
 * <a href="https://www.w3.org/Protocols/rfc1341/7_2_Multipart.html">multiple part messages</a>.
 */
public interface Multipart extends Publisher<HttpData> {

    // Forked form https://github.com/oracle/helidon/blob/ab23ce10cb55043e5e4beea1037a65bb8968354b/media/multipart/src/main/java/io/helidon/media/multipart/MultiPart.java

    /**
     * Returns a new {@link Multipart} with the specified {@link BodyPart}s.
     */
    static Multipart of(BodyPart... parts) {
        return of(DEFAULT_BOUNDARY, parts);
    }

    /**
     * Returns a new {@link Multipart} with the specified {@link BodyPart}s.
     */
    static Multipart of(Iterable<? extends BodyPart> parts) {
        return of(DEFAULT_BOUNDARY, parts);
    }

    /**
     * Returns a new {@link Multipart} with the specified {@code boundary} and {@link BodyPart}s.
     */
    static Multipart of(String boundary, BodyPart... parts) {
        requireNonNull(parts, "parts");
        return of(boundary, ImmutableList.copyOf(parts));
    }

    /**
     * Returns a new {@link Multipart} with the specified {@code boundary} and {@link BodyPart}s.
     */
    static Multipart of(String boundary, Iterable<? extends BodyPart> parts) {
        requireNonNull(boundary, "boundary");
        requireNonNull(parts, "parts");
        @SuppressWarnings("unchecked")
        final Iterable<BodyPart> cast = (Iterable<BodyPart>) parts;
        return new DefaultMultipart(boundary, Multi.from(cast));
    }

    /**
     * Returns a new {@link Multipart} with the specified {@link BodyPart}s.
     */
    static Multipart of(Publisher<? extends BodyPart> parts) {
        return of(DEFAULT_BOUNDARY, parts);
    }

    /**
     * Returns a new {@link Multipart} with the specified {@link BodyPart}s.
     */
    static Multipart of(String boundary, Publisher<? extends BodyPart> parts) {
        requireNonNull(parts, "parts");
        return new DefaultMultipart(boundary, parts);
    }

    /**
     * Returns a decoded {@link Multipart} from the specified {@link HttpRequest}.
     */
    static Multipart from(HttpRequest request) {
        requireNonNull(request, "request");
        final RequestHeaders headers = request.headers();
        final MediaType mediaType = headers.contentType();
        String boundary = null;
        if (mediaType != null) {
            boundary = Iterables.getFirst(mediaType.parameters().get("boundary"), null);
        }
        if (boundary == null) {
            throw new IllegalStateException("boundary header is missing");
        }

        final Multi<?> contents = Multi.from(request, SubscriptionOption.WITH_POOLED_OBJECTS)
                                       .filter(HttpData.class::isInstance);
        @SuppressWarnings("unchecked")
        final Multi<HttpData> cast = (Multi<HttpData>) contents;
        return from(boundary, cast);
    }

    /**
     * Returns a decoded {@link Multipart} from the the specified {@code boundary} and
     * {@link Publisher} of {@link HttpData}.
     */
    static Multipart from(String boundary, Publisher<HttpData> contents) {
        requireNonNull(boundary, "boundary");
        requireNonNull(contents, "contents");
        final MultipartDecoder decoder = new MultipartDecoder(boundary);
        contents.subscribe(decoder);
        return of(decoder);
    }

    /**
     * Converts this {@link Multipart} into a new complete {@link HttpRequest}.
     */
    default HttpRequest toHttpRequest(String path) {
        requireNonNull(path, "path");
        final MediaType contentType = MediaType.MULTIPART_FORM_DATA.withParameter("boundary", boundary());
        return HttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, path,
                                  HttpHeaderNames.CONTENT_TYPE, contentType.toString()), this);
    }

    /**
     * Converts this {@link Multipart} into a new complete {@link HttpRequest} with the specified
     * {@link RequestHeaders}.
     */
    default HttpRequest toHttpRequest(RequestHeaders requestHeaders) {
        requireNonNull(requestHeaders, "requestHeaders");
        final String mediaTypeString = requestHeaders.get(HttpHeaderNames.CONTENT_TYPE);
        final MediaType contentType;
        if (mediaTypeString != null) {
            contentType = MediaType.parse(mediaTypeString);
            checkArgument("multipart".equals(contentType.type()),
                          "Content-Type: %s (expected: multipart content type)", contentType);
            contentType.withParameter("boundary", boundary());
        } else {
            contentType = MediaType.MULTIPART_FORM_DATA.withParameter("boundary", boundary());
        }

        final RequestHeaders updated = requestHeaders.withMutations(builder -> {
            builder.add(HttpHeaderNames.CONTENT_TYPE, contentType.toString());
        });
        return HttpRequest.of(updated, this);
    }

    /**
     * Returns the boundary string.
     */
    String boundary();

    /**
     * Returns all the nested body parts.
     */
    Publisher<BodyPart> bodyParts();

    /**
     * Aggregates this {@link Multipart}. The returned {@link CompletableFuture} will be notified when
     * the {@link BodyPart}s of the {@link Multipart} is received fully.
     */
    CompletableFuture<AggregatedMultipart> aggregate();
}

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
import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.HttpDeframer;
import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.buffer.ByteBufAllocator;

/**
 * A reactive {@link Multipart} that represents
 * <a href="https://www.w3.org/Protocols/rfc1341/7_2_Multipart.html">multiple part messages</a>.
 */
// TODO(ikhoon): extends StreamMessage?
public interface Multipart extends StreamMessage<HttpData> {

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
        final BodyPart[] bodyParts = Iterables.toArray(parts, BodyPart.class);
        return new DefaultMultipart(boundary, StreamMessage.of(bodyParts));
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
        return new DefaultMultipart(boundary, new PublisherBasedStreamMessage<>(parts));
    }

    /**
     * Returns a decoded {@link Multipart} from the specified {@link HttpRequest}.
     * You can reactively subscribe body parts using the {@link Publisher} of {@link #bodyParts()}:
     * <pre>{@code
     * > import reactor.core.publisher.Flux;
     *
     * > HttpRequest req = ...;
     * > Multipart multiPart = Multipart.from(req);
     *
     * > Flux.from(multiPart.bodyParts())
     * >     .subscribe(bodyPart -> {
     * >         Flux.from(bodyPart.content())
     * >             .map(HttpData::toStringUtf8)
     * >             .collectList()
     * >             .subscribe(contents -> { ... });
     * >     });
     * }</pre>
     * , or aggregate this {@link Multipart} using {@link #aggregate()}:
     * <pre>{@code
     * > Multipart.from(req).aggregate()
     * >          .thenAccept(multipart -> {
     * >              for (AggregatedBodyPart bodyPart : multipart.bodyParts()) {
     * >                  String content = bodyPart.contentUtf8();
     * >                  ...
     * >              }
     * >          });
     * }</pre>
     *
     * @see #bodyParts()
     * @see #aggregate()
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

        final StreamMessage<?> contents = StreamMessages.filter(request, HttpData.class::isInstance);
        @SuppressWarnings("unchecked")
        final StreamMessage<HttpData> cast = (StreamMessage<HttpData>) contents;
        return from(boundary, cast);
    }

    /**
     * Returns a decoded {@link Multipart} from the the specified {@code boundary} and
     * {@link Publisher} of {@link HttpData}.
     */
    static Multipart from(String boundary, Publisher<? extends HttpData> contents) {
        return from(boundary, contents, ByteBufAllocator.DEFAULT);
    }

    /**
     * Returns a decoded {@link Multipart} from the the specified {@code boundary},
     * {@link Publisher} of {@link HttpData} and {@link ByteBufAllocator}.
     */
    static Multipart from(String boundary, Publisher<? extends HttpData> contents, ByteBufAllocator alloc) {
        requireNonNull(boundary, "boundary");
        requireNonNull(contents, "contents");
        requireNonNull(alloc, "alloc");
        final MultipartDecoder decoder = new MultipartDecoder(boundary, alloc);
        contents.subscribe(decoder);
        return of(decoder);
    }

    /**
     * Converts this {@link Multipart} into a new complete {@link HttpRequest}.
     * This method is commonly used to send a multipart request to the specified {@code path} of an endpoint.
     *
     * <p>For example:<pre>{@code
     * > HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.CONTENT_DISPOSITION,
     * >                                      ContentDisposition.of("form-data", "file", "test.txt"));
     * > byte[] fileData = ...;
     * > BodyPart filePart = BodyPart.builder()
     * >                             .headers(headers)
     * >                             .content(fileData)
     * >                             .build();
     *
     * > HttpRequest request = Multipart.of(filePart).toHttpRequest("/upload");
     * > CompletableFuture<AggregatedHttpResponse> response = client.execute(request).aggregate();
     * }</pre>
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
     * This method is commonly used to send a multipart request using the specified {@link RequestHeaders}.
     *
     * <p>For example:<pre>{@code
     * > HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.CONTENT_DISPOSITION,
     * >                                      ContentDisposition.of("form-data", "file", "test.txt"));
     * > byte[] fileData = ...;
     * > BodyPart filePart = BodyPart.builder()
     * >                             .headers(headers)
     * >                             .content(fileData)
     * >                             .build();
     *
     * > RequestHeaders requestHeaders = RequestHeaders.of(HttpMethod.POST, "/upload");
     * > HttpRequest request = Multipart.of(filePart).toHttpRequest(requestHeaders);
     * > CompletableFuture<AggregatedHttpResponse> response = client.execute(request).aggregate();
     * }</pre>
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
     *
     * <p>Note: Once a {@link BodyPart} is subscribed, you should subscribe to {@link BodyPart#content()}
     * before subscribing to the next {@link BodyPart}.<pre>{@code
     * > import reactor.core.publisher.Flux;
     *
     * > HttpRequest req = ...;
     * > Multipart multiPart = Multipart.from(req);
     *
     * > // Good:
     * > Flux.from(multiPart.bodyParts())
     * >     .subscribe(bodyPart -> {
     * >         Flux.from(bodyPart.content()) // Safely subscribe to BodyPart.content()
     * >             .map(HttpData::toStringUtf8)
     * >             .collectList()
     * >             .subscribe(contents -> { ... });
     * >     });
     *
     * > // Bad:
     * > Flux.from(multiPart.bodyParts())
     * >     .collectList() // This will subscribe BodyPart.content() first before you subscribe to it.
     * >     .subscribe(bodyParts -> {
     * >         bodyParts.forEach(part -> {
     * >             Flux.from(part.content())
     * >                 .collectList() // Throws IllegalStateException("Only single subscriber is allowed")
     * >                 .subscribe(contents -> { ... });
     * >         });
     * >     });
     * } </pre>
     * If you don't know what this means, use {@link #aggregate()}.
     */
    @CheckReturnValue
    StreamMessage<BodyPart> bodyParts();

    /**
     * Aggregates this {@link Multipart}. The returned {@link CompletableFuture} will be notified when
     * the {@link BodyPart}s of the {@link Multipart} is received fully.
     *
     * <p>For example: <pre>{@code
     * > HttpRequest req = ...;
     * > Multipart.from(req).aggregate()
     * >          .thenAccept(multipart -> {
     * >              for (AggregatedBodyPart bodyPart : multipart.bodyParts()) {
     * >                  String content = bodyPart.contentUtf8();
     * >                  ...
     * >              }
     * >          });
     * }</pre>
     */
    CompletableFuture<AggregatedMultipart> aggregate();
}

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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.common.multipart.DefaultMultipart.randomBoundary;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Publisher;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A reactive {@link Multipart} that represents
 * <a href="https://datatracker.ietf.org/doc/html/rfc1341#page-28">Multiple part messages</a>.
 */
public interface Multipart {

    /**
     * Returns a new {@link Multipart} with the specified {@link BodyPart}s.
     */
    static Multipart of(BodyPart... parts) {
        return of(randomBoundary(), parts);
    }

    /**
     * Returns a new {@link Multipart} with the specified {@link BodyPart}s.
     */
    static Multipart of(Iterable<? extends BodyPart> parts) {
        return of(randomBoundary(), parts);
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
        return of(randomBoundary(), parts);
    }

    /**
     * Returns a new {@link Multipart} with the specified {@link BodyPart}s.
     */
    static Multipart of(String boundary, Publisher<? extends BodyPart> parts) {
        requireNonNull(parts, "parts");
        return new DefaultMultipart(boundary, StreamMessage.of(parts));
    }

    /**
     * Returns a decoded {@link Multipart} from the specified {@link HttpRequest}.
     * You can reactively subscribe to body parts using the {@link Publisher} of {@link #bodyParts()}:
     * <pre>{@code
     * import reactor.core.publisher.Flux;
     *
     * HttpRequest req = ...;
     * Multipart multiPart = Multipart.from(req);
     *
     * Flux.from(multiPart.bodyParts())
     *     .subscribe(bodyPart -> {
     *         Flux.from(bodyPart.content())
     *             .map(HttpData::toStringUtf8)
     *             .collectList()
     *             .subscribe(contents -> { ... });
     *     });
     * }</pre>
     * , or aggregate this {@link Multipart} using {@link #aggregate()}:
     * <pre>{@code
     * Multipart.from(req).aggregate()
     *          .thenAccept(multipart -> {
     *              for (AggregatedBodyPart bodyPart : multipart.bodyParts()) {
     *                  String content = bodyPart.contentUtf8();
     *                  ...
     *              }
     *          });
     * }</pre>
     *
     * @see #bodyParts()
     * @see #aggregate()
     */
    static Multipart from(HttpRequest request) {
        requireNonNull(request, "request");
        final RequestHeaders headers = request.headers();
        @Nullable
        final MediaType mediaType = headers.contentType();
        checkState(mediaType != null, "Content-Type header is missing");
        final String boundary = Multiparts.getBoundary(mediaType);

        @SuppressWarnings("unchecked")
        final StreamMessage<HttpData> cast =
                (StreamMessage<HttpData>) (StreamMessage<?>) request.filter(HttpData.class::isInstance);
        return from(boundary, cast);
    }

    /**
     * Returns a decoded {@link Multipart} from the the specified {@code boundary} and
     * {@link Publisher} of {@link HttpData}.
     * For instance, {@link Multipart} could be decoded from the specified {@link HttpResponse}
     * in the following way:
     * <pre>{@code
     * HttpResponse response = ...;
     * SplitHttpResponse splitResponse = response.split();
     * ResponseHeaders responseHeaders = splitResponse.headers().join();
     * StreamMessage<HttpData> responseContents = splitResponse.body();
     * MediaType contentType = responseHeaders.contentType();
     * if (contentType != null && contentType.isMultipart()) {
     *     String boundary = Multiparts.getBoundary(contentType);
     *     Multipart multipart = Multipart.from(boundary, responseContents);
     *     ...
     * } else {
     *     handleNonMultipartResponse(responseHeaders, responseContents);
     * }
     * }</pre>
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
        final MultipartDecoder decoder = new MultipartDecoder(StreamMessage.of(contents), boundary, alloc);
        return of(boundary, decoder);
    }

    /**
     * Converts this {@link Multipart} into a new complete {@link HttpRequest}.
     * This method is commonly used to send a multipart request to the specified {@code path} of an endpoint.
     *
     * <p>For example:
     * <pre>{@code
     * HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.CONTENT_DISPOSITION,
     *                                      ContentDisposition.of("form-data", "file", "test.txt"));
     * byte[] fileData = ...;
     * BodyPart filePart = BodyPart.builder()
     *                             .headers(headers)
     *                             .content(fileData)
     *                             .build();
     *
     * HttpRequest request = Multipart.of(filePart).toHttpRequest("/upload");
     * CompletableFuture<AggregatedHttpResponse> response = client.execute(request).aggregate();
     * }</pre>
     */
    HttpRequest toHttpRequest(String path);

    /**
     * Converts this {@link Multipart} into a new complete {@link HttpRequest} with the specified
     * {@link RequestHeaders}.
     * This method is commonly used to send a multipart request using the specified {@link RequestHeaders}.
     *
     * <p>For example:
     * <pre>{@code
     * HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.CONTENT_DISPOSITION,
     *                                      ContentDisposition.of("form-data", "file", "test.txt"));
     * byte[] fileData = ...;
     * BodyPart filePart = BodyPart.builder()
     *                             .headers(headers)
     *                             .content(fileData)
     *                             .build();
     *
     * RequestHeaders requestHeaders = RequestHeaders.builder(HttpMethod.POST, "/upload")
     *                                               .contentType(MediaType.MULTIPART_RELATED)
     *                                               .build();
     * HttpRequest request = Multipart.of(filePart).toHttpRequest(requestHeaders);
     * CompletableFuture<AggregatedHttpResponse> response = client.execute(request).aggregate();
     * }</pre>
     */
    HttpRequest toHttpRequest(RequestHeaders requestHeaders);

    /**
     * Converts this {@link Multipart} into a new complete {@link HttpResponse}.
     * This method is commonly used to send a multipart response with the specified {@link HttpStatus}.
     *
     * <p>For example:
     * <pre>{@code
     * HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.CONTENT_DISPOSITION,
     *                                      ContentDisposition.of("form-data", "file", "test.txt"));
     * byte[] fileData = ...;
     * BodyPart filePart = BodyPart.builder()
     *                             .headers(headers)
     *                             .content(fileData)
     *                             .build();
     *
     * HttpResponse response = Multipart.of(filePart).toHttpResponse(HttpStatus.OK);
     * }</pre>
     */
    HttpResponse toHttpResponse(HttpStatus status);

    /**
     * Converts this {@link Multipart} into a new complete {@link HttpResponse} with the specified
     * {@link ResponseHeaders}.
     * This method is commonly used to send a multipart response using the specified {@link ResponseHeaders}.
     *
     * <p>For example:
     * <pre>{@code
     * HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.CONTENT_DISPOSITION,
     *                                      ContentDisposition.of("form-data", "file", "test.txt"));
     * byte[] fileData = ...;
     * BodyPart filePart = BodyPart.builder()
     *                             .headers(headers)
     *                             .content(fileData)
     *                             .build();
     *
     * ResponseHeaders responseHeaders = ResponseHeaders.builder(HttpStatus.OK)
     *                                                  .contentType(MediaType.MULTIPART_RELATED)
     *                                                  .build();
     * HttpResponse response = Multipart.of(filePart).toHttpResponse(responseHeaders);
     * }</pre>
     */
    HttpResponse toHttpResponse(ResponseHeaders responseHeaders);

    /**
     * Returns a {@link StreamMessage} that emits the {{@link #bodyParts()}} as a stream of {@link HttpData}.
     */
    @CheckReturnValue
    StreamMessage<HttpData> toStreamMessage();

    /**
     * Returns the boundary string.
     */
    String boundary();

    /**
     * Returns all the nested body parts.
     *
     * <p>Note: Once a {@link BodyPart} is subscribed, you should subscribe to {@link BodyPart#content()}
     * before subscribing to the next {@link BodyPart}.
     * <pre>{@code
     * import reactor.core.publisher.Flux;
     *
     * HttpRequest req = ...;
     * Multipart multiPart = Multipart.from(req);
     *
     * // Good:
     * Flux.from(multiPart.bodyParts())
     *     .subscribe(bodyPart -> {
     *         Flux.from(bodyPart.content()) // Safely subscribe to BodyPart.content()
     *             .map(HttpData::toStringUtf8)
     *             .collectList()
     *             .subscribe(contents -> { ... });
     *     });
     *
     * // Bad:
     * Flux.from(multiPart.bodyParts())
     *     .collectList() // This will subscribe BodyPart.content() first before you subscribe to it.
     *     .subscribe(bodyParts -> {
     *         bodyParts.forEach(part -> {
     *             Flux.from(part.content())
     *                 .collectList() // Throws IllegalStateException("Only single subscriber is allowed")
     *                 .subscribe(contents -> { ... });
     *         });
     *     });
     * } </pre>
     * If you don't know what this means, use {@link #aggregate()}.
     */
    @CheckReturnValue
    StreamMessage<BodyPart> bodyParts();

    /**
     * Aggregates this {@link Multipart}. The returned {@link CompletableFuture} will be notified when
     * the {@link BodyPart}s of the {@link Multipart} is received fully.
     *
     * <p>For example:
     * <pre>{@code
     * HttpRequest req = ...;
     * Multipart.from(req).aggregate()
     *          .thenAccept(multipart -> {
     *              for (AggregatedBodyPart bodyPart : multipart.bodyParts()) {
     *                  String content = bodyPart.contentUtf8();
     *                  ...
     *              }
     *          });
     * }</pre>
     */
    CompletableFuture<AggregatedMultipart> aggregate();

    /**
     * Aggregates this {@link Multipart} with the specified {@link EventExecutor}.
     * The returned {@link CompletableFuture} will be notified when
     * the {@link BodyPart}s of the {@link Multipart} is received fully.
     *
     * <p>For example:
     * <pre>{@code
     * HttpRequest req = ...;
     * EventExecutor executor = ...;
     * Multipart.from(req).aggregate(executor)
     *          .thenAccept(multipart -> {
     *              for (AggregatedBodyPart bodyPart : multipart.bodyParts()) {
     *                  String content = bodyPart.contentUtf8();
     *                  ...
     *              }
     *          });
     * }</pre>
     */
    CompletableFuture<AggregatedMultipart> aggregate(EventExecutor executor);

    /**
     * (Advanced users only) Aggregates this {@link Multipart}. The returned {@link CompletableFuture} will
     * be notified when the {@link BodyPart}s of the {@link Multipart} is received fully.
     * {@link AggregatedBodyPart#content()} will return a pooled object, and the caller must ensure
     * to release it. If you don't know what this means, use {@link #aggregate()}.
     */
    CompletableFuture<AggregatedMultipart> aggregateWithPooledObjects(ByteBufAllocator alloc);

    /**
     * (Advanced users only) Aggregates this {@link Multipart}. The returned {@link CompletableFuture} will
     * be notified when the {@link BodyPart}s of the {@link Multipart} is received fully.
     * {@link AggregatedBodyPart#content()} will return a pooled object, and the caller must ensure
     * to release it. If you don't know what this means, use {@link #aggregate()}.
     */
    CompletableFuture<AggregatedMultipart> aggregateWithPooledObjects(EventExecutor executor,
                                                                      ByteBufAllocator alloc);
}

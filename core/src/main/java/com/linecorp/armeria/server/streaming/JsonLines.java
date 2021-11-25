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
package com.linecorp.armeria.server.streaming;

import static com.linecorp.armeria.internal.server.ResponseConversionUtil.streamingFrom;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * A utility class which helps to create a <a href="https://jsonlines.org/">JavaScript Object
 * Notation (JSON) Lines text</a> from a content {@link Publisher} or {@link Stream}.
 *
 * <p>A user simply creates a streaming {@link HttpResponse} which emits JSON Lines text, e.g.
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.WRITE_ENUMS_USING_INDEX);
 * Server server =
 *     Server.builder()
 *           // Emit JSON Lines Text with a default ObjectMapper.
 *           .service("/seq1",
 *                    (ctx, req) -> JsonLines.fromPublisher(Flux.just("foo", "bar")))
 *           // Emit JSON Lines Text with the ObjectMapper
 *           // configured to use SerializationFeature.WRITE_ENUMS_USING_INDEX.
 *           .service("/seq2",
 *                    (ctx, req) -> JsonLines.fromPublisher(Flux.just("foo", "bar"), mapper))
 *           .build();
 * }</pre>
 */
public final class JsonLines {
    private static final Logger logger = LoggerFactory.getLogger(JsonLines.class);

    /**
     * A flag whether a warn log has been written if the given status code is not the {@link HttpStatus#OK}.
     */
    private static boolean warnedStatusCode;

    /**
     * A flag whether a warn log has been written if the given content type is not the
     * {@link MediaType#JSON_LINES}.
     */
    private static boolean warnedContentType;

    /**
     * A line feed which indicates the end of a JSON Line.
     */
    private static final byte LINE_FEED = 0x0A;

    /**
     * A default {@link ObjectMapper} which converts the objects into JSON Line.
     */
    private static final ObjectMapper defaultMapper =
            JacksonUtil.newDefaultObjectMapper().disable(SerializationFeature.INDENT_OUTPUT);

    /**
     * A default {@link ResponseHeaders} of JSON Lines.
     */
    private static final ResponseHeaders defaultHttpHeaders = ResponseHeaders.builder(HttpStatus.OK)
                                                                             .contentType(MediaType.JSON_LINES)
                                                                             .build();

    /**
     * Returns a newly created JSON Lines response from the specified {@link Publisher}.
     *
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     */
    public static HttpResponse fromPublisher(Publisher<?> contentPublisher) {
        return fromPublisher(defaultHttpHeaders, contentPublisher, HttpHeaders.of(), defaultMapper);
    }

    /**
     * Returns a newly created JSON Lines response from specified {@link Publisher}.
     *
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param mapper the mapper which converts the content object into JSON Lines
     */
    public static HttpResponse fromPublisher(Publisher<?> contentPublisher, ObjectMapper mapper) {
        return fromPublisher(defaultHttpHeaders, contentPublisher, HttpHeaders.of(), mapper);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     */
    public static HttpResponse fromPublisher(ResponseHeaders headers, Publisher<?> contentPublisher) {
        return fromPublisher(headers, contentPublisher, HttpHeaders.of(), defaultMapper);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param mapper the mapper which converts the content object into JSON Lines
     */
    public static HttpResponse fromPublisher(ResponseHeaders headers, Publisher<?> contentPublisher,
                                             ObjectMapper mapper) {
        return fromPublisher(headers, contentPublisher, HttpHeaders.of(), mapper);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param trailers the HTTP trailers
     * @param mapper the mapper which converts the content object into JSON Lines
     */
    public static HttpResponse fromPublisher(ResponseHeaders headers, Publisher<?> contentPublisher,
                                             HttpHeaders trailers, ObjectMapper mapper) {
        requireNonNull(mapper, "mapper");
        return streamingFrom(contentPublisher, sanitizeHeaders(headers), trailers, o -> toHttpData(mapper, o));
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@link Publisher}.
     *
     * <p>Note that this method is intentionally hidden from the public API for use only with
     * {@code ScalaPbResponseConverterFunction} and {@code ProtobufResponseConverterFunction}.
     * Because the {@code contentConverter} can easily produce a malformed string which violates the
     * <a href="https://jsonlines.org/">JSON format</a>.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param trailers the HTTP trailers
     * @param contentConverter the function which converts the content object into a UTF-8 JSON string.
     */
    @SuppressWarnings("unused")
    static <T> HttpResponse fromPublisher(ResponseHeaders headers, Publisher<T> contentPublisher,
                                          HttpHeaders trailers,
                                          Function<? super T, String> contentConverter) {
        requireNonNull(contentConverter, "contentConverter");
        return streamingFrom(contentPublisher, sanitizeHeaders(headers), trailers,
                o -> toHttpData(contentConverter, o));
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@link Stream}.
     *
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(Stream<?> contentStream, Executor executor) {
        return fromStream(defaultHttpHeaders, contentStream, HttpHeaders.of(), executor, defaultMapper);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@link Stream}.
     *
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(Stream<?> contentStream, Executor executor,
                                          ObjectMapper mapper) {
        return fromStream(defaultHttpHeaders, contentStream, HttpHeaders.of(), executor, mapper);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(ResponseHeaders headers, Stream<?> contentStream,
                                          Executor executor) {
        return fromStream(headers, contentStream, HttpHeaders.of(), executor, defaultMapper);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     * @param mapper the mapper which converts the content object into JSON Lines
     */
    public static HttpResponse fromStream(ResponseHeaders headers, Stream<?> contentStream,
                                          Executor executor, ObjectMapper mapper) {
        return fromStream(headers, contentStream, HttpHeaders.of(), executor, mapper);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param trailers the HTTP trailers
     * @param executor the executor which iterates the stream
     * @param mapper the mapper which converts the content object into JSON Lines
     */
    public static HttpResponse fromStream(ResponseHeaders headers, Stream<?> contentStream,
                                          HttpHeaders trailers, Executor executor,
                                          ObjectMapper mapper) {
        requireNonNull(mapper, "mapper");
        return streamingFrom(contentStream, sanitizeHeaders(headers), trailers,
                o -> toHttpData(mapper, o), executor);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@link Stream}.
     *
     * <p>Note that this method is intentionally hidden from the public API for use only with
     * {@code ScalaPbResponseConverterFunction} and {@code ProtobufResponseConverterFunction}.
     * Because the {@code contentConverter} can easily produce a malformed string which violates the
     * <a href="https://jsonlines.org/">JSON Lines format</a>.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param trailers the HTTP trailers
     * @param executor the executor which iterates the stream
     * @param contentConverter the function which converts the content object into a UTF-8 JSON string
     */
    @SuppressWarnings("unused")
    static <T> HttpResponse fromStream(ResponseHeaders headers, Stream<T> contentStream,
                                       HttpHeaders trailers, Executor executor,
                                       Function<? super T, String> contentConverter) {
        requireNonNull(contentConverter, "contentConverter");
        return streamingFrom(contentStream, sanitizeHeaders(headers), trailers,
                o -> toHttpData(contentConverter, o), executor);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@code content}.
     *
     * @param content the object supposed to send as contents
     */
    public static HttpResponse fromObject(@Nullable Object content) {
        return fromObject(defaultHttpHeaders, content, HttpHeaders.of(), defaultMapper);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     */
    public static HttpResponse fromObject(ResponseHeaders headers, @Nullable Object content) {
        return fromObject(headers, content, HttpHeaders.of(), defaultMapper);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     * @param mapper the mapper which converts the content object into JSON Lines
     */
    public static HttpResponse fromObject(ResponseHeaders headers, @Nullable Object content,
                                          ObjectMapper mapper) {
        return fromObject(headers, content, HttpHeaders.of(), mapper);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     * @param trailers the HTTP trailers
     * @param mapper the mapper which converts the content object into JSON Lines
     */
    public static HttpResponse fromObject(ResponseHeaders headers, @Nullable Object content,
                                          HttpHeaders trailers, ObjectMapper mapper) {
        requireNonNull(headers, "headers");
        requireNonNull(trailers, "trailers");
        requireNonNull(mapper, "mapper");
        return HttpResponse.of(sanitizeHeaders(headers), toHttpData(mapper, content), trailers);
    }

    /**
     * Returns a newly created JSON Lines response from the specified {@code content}.
     *
     * <p>Note that this method is intentionally hidden from the public API for use only with
     * {@code ScalaPbResponseConverterFunction} and {@code ProtobufResponseConverterFunction}.
     * Because the {@code contentConverter} can easily produce a malformed string which violates the
     * <a href="https://jsonlines.org/">JSON format</a>.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     * @param trailers the HTTP trailers
     * @param contentConverter the function which converts the content object into a UTF-8 JSON string.
     */
    @SuppressWarnings("unused")
    static <T> HttpResponse fromObject(ResponseHeaders headers, @Nullable T content,
                                       HttpHeaders trailers,
                                       Function<? super T, String> contentConverter) {
        requireNonNull(headers, "headers");
        requireNonNull(trailers, "trailers");
        requireNonNull(contentConverter, "contentConverter");
        return HttpResponse.of(sanitizeHeaders(headers), toHttpData(contentConverter, content), trailers);
    }

    private static ResponseHeaders sanitizeHeaders(ResponseHeaders headers) {
        requireNonNull(headers, "headers");
        if (headers == defaultHttpHeaders) {
            return headers;
        }
        return ensureContentType(ensureHttpStatus(headers));
    }

    static ResponseHeaders ensureHttpStatus(ResponseHeaders headers) {
        final HttpStatus status = headers.status();
        if (status == HttpStatus.OK) {
            return headers;
        }

        if (!warnedStatusCode) {
            logger.warn(
                    "Overwriting the HTTP status code from '{}' to '{}' for JSON Lines. " +
                    "Do not set an HTTP status code on the HttpHeaders when calling factory methods in '{}', " +
                    "or set '{}' if you want to specify its status code. " +
                    "Please refer to https://jsonlines.org/ for more information.",
                    status, HttpStatus.OK, JsonLines.class.getSimpleName(), HttpStatus.OK);
            warnedStatusCode = true;
        }
        return headers.toBuilder().status(HttpStatus.OK).build();
    }

    static ResponseHeaders ensureContentType(ResponseHeaders headers) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return headers.toBuilder()
                          .contentType(MediaType.JSON_LINES)
                          .build();
        }

        if (contentType.is(MediaType.JSON_LINES)) {
            return headers;
        }

        if (!warnedContentType) {
            logger.warn("Overwriting content-type from '{}' to '{}' for JSON Lines. " +
                        "Do not set a content-type on the HttpHeaders when calling factory methods in '{}', " +
                        "or set '{}' if you want to specify its content-type. " +
                        "Please refer to https://jsonlines.org/ for more information.",
                    contentType, MediaType.JSON_LINES,
                    JsonLines.class.getSimpleName(), MediaType.JSON_LINES);
            warnedContentType = true;
        }
        return headers.toBuilder()
                      .contentType(MediaType.JSON_LINES)
                      .build();
    }

    private static HttpData toHttpData(ObjectMapper mapper, @Nullable Object value) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            // If the mapper is same don't need disable indentation.
            if (mapper == defaultMapper) {
                mapper.writeValue(out, value);
            } else {
                final JsonNode root = mapper.valueToTree(value);
                defaultMapper.writeValue(out, root);
            }
            out.write(LINE_FEED);
            return HttpData.wrap(out.toByteArray());
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private static <T> HttpData toHttpData(Function<? super T, String> contentConverter, @Nullable T value) {
        final String content = contentConverter.apply(value);
        requireNonNull(content, "contentConverter.apply() returned null");
        final byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        final int contentLength = contentBytes.length;
        final byte[] jsonText = new byte[contentLength + 1];
        System.arraycopy(contentBytes, 0, jsonText, 1, contentLength);
        jsonText[contentLength + 1] = LINE_FEED;
        return HttpData.wrap(jsonText);
    }

    private JsonLines() {}
}

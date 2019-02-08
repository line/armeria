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
package com.linecorp.armeria.server.streaming;

import static com.linecorp.armeria.internal.ResponseConversionUtil.streamingFrom;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A utility class which helps to create a <a href="https://tools.ietf.org/html/rfc7464">JavaScript Object
 * Notation (JSON) Text Sequences</a> from a content {@link Publisher} or {@link Stream}.
 *
 * <p>A user simply creates a streaming {@link HttpResponse} which emits JSON Text Sequences, e.g.
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
 * Server server = new ServerBuilder()
 *         // Emit JSON Text Sequences with a default ObjectMapper.
 *         .service("/seq1",
 *                  (ctx, req) -> JsonTextSequences.fromPublisher(Flux.just("foo", "bar")))
 *         // Emit JSON Text Sequences with the ObjectMapper configured as using a default pretty printer.
 *         .service("/seq2",
 *                  (ctx, req) -> JsonTextSequences.fromPublisher(Flux.just("foo", "bar"), mapper))
 *         .build();
 * }</pre>
 */
public final class JsonTextSequences {

    private static final Logger logger = LoggerFactory.getLogger(JsonTextSequences.class);

    /**
     * A flag whether a warn log has been written if the given status code is not the {@link HttpStatus#OK}.
     */
    private static boolean warnedStatusCode;

    /**
     * A flag whether a warn log has been written if the given content type is not the
     * {@link MediaType#JSON_SEQ}.
     */
    private static boolean warnedContentType;

    /**
     * A record separator which indicates the beginning of a JSON text.
     */
    private static final byte RECORD_SEPARATOR = 0x1E;

    /**
     * A line feed which indicates the end of a JSON text.
     */
    private static final byte LINE_FEED = 0x0A;

    /**
     * A default {@link ObjectMapper} which converts the objects into JSON Text Sequences.
     */
    private static final ObjectMapper defaultMapper = new ObjectMapper();

    /**
     * A default {@link HttpHeaders} of JSON Text Sequences.
     */
    private static final HttpHeaders defaultHttpHeaders =
            HttpHeaders.of(HttpStatus.OK).contentType(MediaType.JSON_SEQ).asImmutable();

    /**
     * Creates a new JSON Text Sequences from the specified {@link Publisher}.
     *
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     */
    public static HttpResponse fromPublisher(Publisher<?> contentPublisher) {
        return fromPublisher(defaultHttpHeaders, contentPublisher, HttpHeaders.EMPTY_HEADERS, defaultMapper);
    }

    /**
     * Creates a new JSON Text Sequences from the specified {@link Publisher}.
     *
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param mapper the mapper which converts the content object into JSON Text Sequences
     */
    public static HttpResponse fromPublisher(Publisher<?> contentPublisher, ObjectMapper mapper) {
        return fromPublisher(defaultHttpHeaders, contentPublisher, HttpHeaders.EMPTY_HEADERS, mapper);
    }

    /**
     * Creates a new JSON Text Sequences from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     */
    public static HttpResponse fromPublisher(HttpHeaders headers, Publisher<?> contentPublisher) {
        return fromPublisher(headers, contentPublisher, HttpHeaders.EMPTY_HEADERS, defaultMapper);
    }

    /**
     * Creates a new JSON Text Sequences from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param mapper the mapper which converts the content object into JSON Text Sequences
     */
    public static HttpResponse fromPublisher(HttpHeaders headers, Publisher<?> contentPublisher,
                                             ObjectMapper mapper) {
        return fromPublisher(headers, contentPublisher, HttpHeaders.EMPTY_HEADERS, mapper);
    }

    /**
     * Creates a new JSON Text Sequences from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     * @param mapper the mapper which converts the content object into JSON Text Sequences
     */
    public static HttpResponse fromPublisher(HttpHeaders headers, Publisher<?> contentPublisher,
                                             HttpHeaders trailingHeaders, ObjectMapper mapper) {
        requireNonNull(mapper, "mapper");
        return streamingFrom(contentPublisher, sanitizeHeaders(headers), trailingHeaders,
                             o -> toHttpData(mapper, o));
    }

    /**
     * Creates a new JSON Text Sequences from the specified {@link Stream}.
     *
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(Stream<?> contentStream, Executor executor) {
        return fromStream(defaultHttpHeaders, contentStream, HttpHeaders.EMPTY_HEADERS, executor,
                          defaultMapper);
    }

    /**
     * Creates a new JSON Text Sequences from the specified {@link Stream}.
     *
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(Stream<?> contentStream, Executor executor,
                                          ObjectMapper mapper) {
        return fromStream(defaultHttpHeaders, contentStream, HttpHeaders.EMPTY_HEADERS, executor, mapper);
    }

    /**
     * Creates a new JSON Text Sequences from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(HttpHeaders headers, Stream<?> contentStream,
                                          Executor executor) {
        return fromStream(headers, contentStream, HttpHeaders.EMPTY_HEADERS, executor, defaultMapper);
    }

    /**
     * Creates a new JSON Text Sequences from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     * @param mapper the mapper which converts the content object into JSON Text Sequences
     */
    public static HttpResponse fromStream(HttpHeaders headers, Stream<?> contentStream,
                                          Executor executor, ObjectMapper mapper) {
        return fromStream(headers, contentStream, HttpHeaders.EMPTY_HEADERS, executor, mapper);
    }

    /**
     * Creates a new JSON Text Sequences from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     * @param executor the executor which iterates the stream
     * @param mapper the mapper which converts the content object into JSON Text Sequences
     */
    public static HttpResponse fromStream(HttpHeaders headers, Stream<?> contentStream,
                                          HttpHeaders trailingHeaders, Executor executor,
                                          ObjectMapper mapper) {
        requireNonNull(mapper, "mapper");
        return streamingFrom(contentStream, sanitizeHeaders(headers), trailingHeaders,
                             o -> toHttpData(mapper, o), executor);
    }

    /**
     * Creates a new JSON Text Sequences of the specified {@code content}.
     *
     * @param content the object supposed to send as contents
     */
    public static HttpResponse fromObject(@Nullable Object content) {
        return fromObject(defaultHttpHeaders, content, HttpHeaders.EMPTY_HEADERS, defaultMapper);
    }

    /**
     * Creates a new JSON Text Sequences of the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     */
    public static HttpResponse fromObject(HttpHeaders headers, @Nullable Object content) {
        return fromObject(headers, content, HttpHeaders.EMPTY_HEADERS, defaultMapper);
    }

    /**
     * Creates a new JSON Text Sequences of the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     * @param mapper the mapper which converts the content object into JSON Text Sequences
     */
    public static HttpResponse fromObject(HttpHeaders headers, @Nullable Object content,
                                          ObjectMapper mapper) {
        return fromObject(headers, content, HttpHeaders.EMPTY_HEADERS, mapper);
    }

    /**
     * Creates a new JSON Text Sequences of the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     * @param mapper the mapper which converts the content object into JSON Text Sequences
     */
    public static HttpResponse fromObject(HttpHeaders headers, @Nullable Object content,
                                          HttpHeaders trailingHeaders, ObjectMapper mapper) {
        requireNonNull(headers, "headers");
        requireNonNull(trailingHeaders, "trailingHeaders");
        requireNonNull(mapper, "mapper");
        return HttpResponse.of(sanitizeHeaders(headers), toHttpData(mapper, content), trailingHeaders);
    }

    private static HttpHeaders sanitizeHeaders(HttpHeaders headers) {
        if (headers == defaultHttpHeaders) {
            return headers;
        }
        return ensureContentType(ensureHttpStatus(headers));
    }

    static HttpHeaders ensureHttpStatus(HttpHeaders headers) {
        final HttpStatus status = headers.status();
        if (status == null) {
            return headers.toMutable().status(HttpStatus.OK);
        }

        if (status.equals(HttpStatus.OK)) {
            return headers;
        }

        if (!warnedStatusCode) {
            logger.warn(
                    "Overwriting the HTTP status code from '{}' to '{}' for JSON Text Sequences. " +
                    "Do not set an HTTP status code on the HttpHeaders when calling factory methods in '{}', " +
                    "or set '{}' if you want to specify its status code. " +
                    "Please refer to https://tools.ietf.org/html/rfc7464 for more information.",
                    status, HttpStatus.OK, JsonTextSequences.class.getSimpleName(), HttpStatus.OK);
            warnedStatusCode = true;
        }
        return headers.toMutable().status(HttpStatus.OK);
    }

    static HttpHeaders ensureContentType(HttpHeaders headers) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return headers.toMutable().contentType(MediaType.JSON_SEQ);
        }

        if (contentType.is(MediaType.JSON_SEQ)) {
            return headers;
        }

        if (!warnedContentType) {
            logger.warn("Overwriting content-type from '{}' to '{}' for JSON Text Sequences. " +
                        "Do not set a content-type on the HttpHeaders when calling factory methods in '{}', " +
                        "or set '{}' if you want to specify its content-type. " +
                        "Please refer to https://tools.ietf.org/html/rfc7464 for more information.",
                        contentType, MediaType.JSON_SEQ,
                        JsonTextSequences.class.getSimpleName(), MediaType.JSON_SEQ);
            warnedContentType = true;
        }
        return headers.toMutable().contentType(MediaType.JSON_SEQ);
    }

    private static HttpData toHttpData(ObjectMapper mapper, @Nullable Object value) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(RECORD_SEPARATOR);
            mapper.writeValue(out, value);
            out.write(LINE_FEED);
            return HttpData.of(out.toByteArray());
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private JsonTextSequences() {}
}

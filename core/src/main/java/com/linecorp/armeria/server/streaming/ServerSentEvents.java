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

import static com.linecorp.armeria.internal.server.ResponseConversionUtil.streamingFrom;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.sse.ServerSentEvent;

/**
 * A utility class which helps to create a <a href="https://www.w3.org/TR/eventsource/">Server-Sent Events</a>
 * stream from a content {@link Publisher} or {@link Stream}.
 *
 * <p>A user simply creates a streaming {@link HttpResponse} which emits Server-Sent Events, e.g.
 * <pre>{@code
 * Server server =
 *     Server.builder()
 *           // Emit Server-Sent Events with the SeverSentEvent instances published by a publisher.
 *           .service("/sse1",
 *                    (ctx, req) -> ServerSentEvents.fromPublisher(
 *                            Flux.just(ServerSentEvent.ofData("foo"), ServerSentEvent.ofData("bar"))))
 *           // Emit Server-Sent Events with converting instances published by a publisher into
 *           // ServerSentEvent instances.
 *           .service("/sse2",
 *                    (ctx, req) -> ServerSentEvents.fromPublisher(
 *                            Flux.just("foo", "bar"), ServerSentEvent::ofData))
 *           .build();
 * }</pre>
 */
public final class ServerSentEvents {

    private static final Logger logger = LoggerFactory.getLogger(ServerSentEvents.class);

    /**
     * A flag whether a warn log has been written if the given status code is not the {@link HttpStatus#OK}.
     */
    private static boolean warnedStatusCode;

    /**
     * A flag whether a warn log has been written if the given content type is not the
     * {@link MediaType#EVENT_STREAM}.
     */
    private static boolean warnedContentType;

    /**
     * A line feed character which marks the end of a field in Server-Sent Events.
     */
    private static final char LINE_FEED = '\n';

    /**
     * A default {@link ResponseHeaders} of Server-Sent Events.
     */
    private static final ResponseHeaders defaultHttpHeaders =
            ResponseHeaders.of(HttpStatus.OK,
                               HttpHeaderNames.CONTENT_TYPE, MediaType.EVENT_STREAM);

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Publisher}.
     *
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     */
    public static HttpResponse fromPublisher(Publisher<? extends ServerSentEvent> contentPublisher) {
        return fromPublisher(defaultHttpHeaders, contentPublisher, HttpHeaders.of());
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     */
    public static HttpResponse fromPublisher(ResponseHeaders headers,
                                             Publisher<? extends ServerSentEvent> contentPublisher) {
        return fromPublisher(headers, contentPublisher, HttpHeaders.of());
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param trailers the HTTP trailers
     */
    public static HttpResponse fromPublisher(ResponseHeaders headers,
                                             Publisher<? extends ServerSentEvent> contentPublisher,
                                             HttpHeaders trailers) {
        requireNonNull(headers, "headers");
        requireNonNull(contentPublisher, "contentPublisher");
        requireNonNull(trailers, "trailers");
        return streamingFrom(contentPublisher, sanitizeHeaders(headers), trailers,
                             ServerSentEvents::toHttpData);
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Publisher} and {@code converter}.
     *
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromPublisher(Publisher<T> contentPublisher,
                                                 Function<? super T, ? extends ServerSentEvent> converter) {
        return fromPublisher(defaultHttpHeaders, contentPublisher, HttpHeaders.of(), converter);
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Publisher} and {@code converter}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromPublisher(ResponseHeaders headers,
                                                 Publisher<T> contentPublisher,
                                                 Function<? super T, ? extends ServerSentEvent> converter) {
        return fromPublisher(headers, contentPublisher, HttpHeaders.of(), converter);
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Publisher} and {@code converter}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param trailers the HTTP trailers
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromPublisher(ResponseHeaders headers,
                                                 Publisher<T> contentPublisher,
                                                 HttpHeaders trailers,
                                                 Function<? super T, ? extends ServerSentEvent> converter) {
        requireNonNull(headers, "headers");
        requireNonNull(contentPublisher, "contentPublisher");
        requireNonNull(trailers, "trailers");
        requireNonNull(converter, "converter");
        return streamingFrom(contentPublisher, sanitizeHeaders(headers), trailers,
                             o -> toHttpData(converter, o));
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Stream}.
     *
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(Stream<? extends ServerSentEvent> contentStream,
                                          Executor executor) {
        return fromStream(defaultHttpHeaders, contentStream, HttpHeaders.of(), executor);
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(ResponseHeaders headers,
                                          Stream<? extends ServerSentEvent> contentStream,
                                          Executor executor) {
        return fromStream(headers, contentStream, HttpHeaders.of(), executor);
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param trailers the HTTP trailers
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(ResponseHeaders headers,
                                          Stream<? extends ServerSentEvent> contentStream,
                                          HttpHeaders trailers, Executor executor) {
        requireNonNull(headers, "headers");
        requireNonNull(contentStream, "contentStream");
        requireNonNull(trailers, "trailers");
        requireNonNull(executor, "executor");
        return streamingFrom(contentStream, sanitizeHeaders(headers), trailers,
                             ServerSentEvents::toHttpData, executor);
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Stream} and {@code converter}.
     *
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromStream(Stream<T> contentStream, Executor executor,
                                              Function<? super T, ? extends ServerSentEvent> converter) {
        return fromStream(defaultHttpHeaders, contentStream, HttpHeaders.of(), executor, converter);
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Stream} and {@code converter}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromStream(ResponseHeaders headers,
                                              Stream<T> contentStream, Executor executor,
                                              Function<? super T, ? extends ServerSentEvent> converter) {
        return fromStream(headers, contentStream, HttpHeaders.of(), executor, converter);
    }

    /**
     * Creates a new Server-Sent Events stream from the specified {@link Stream} and {@code converter}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param trailers the HTTP trailers
     * @param executor the executor which iterates the stream
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromStream(ResponseHeaders headers, Stream<T> contentStream,
                                              HttpHeaders trailers, Executor executor,
                                              Function<? super T, ? extends ServerSentEvent> converter) {
        requireNonNull(headers, "headers");
        requireNonNull(contentStream, "contentStream");
        requireNonNull(trailers, "trailers");
        requireNonNull(executor, "executor");
        requireNonNull(converter, "converter");
        return streamingFrom(contentStream, sanitizeHeaders(headers), trailers,
                             o -> toHttpData(converter, o), executor);
    }

    /**
     * Creates a new Server-Sent Events stream of the specified {@code content}.
     *
     * @param sse the {@link ServerSentEvent} object supposed to send as contents
     */
    public static HttpResponse fromEvent(ServerSentEvent sse) {
        return fromEvent(defaultHttpHeaders, sse, HttpHeaders.of());
    }

    /**
     * Creates a new Server-Sent Events stream of the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param sse the {@link ServerSentEvent} object supposed to send as contents
     */
    public static HttpResponse fromEvent(ResponseHeaders headers, ServerSentEvent sse) {
        return fromEvent(headers, sse, HttpHeaders.of());
    }

    /**
     * Creates a new Server-Sent Events stream of the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param sse the {@link ServerSentEvent} object supposed to send as contents
     * @param trailers the HTTP trailers
     */
    public static HttpResponse fromEvent(ResponseHeaders headers, ServerSentEvent sse, HttpHeaders trailers) {
        requireNonNull(headers, "headers");
        requireNonNull(sse, "sse");
        requireNonNull(trailers, "trailers");
        return HttpResponse.of(sanitizeHeaders(headers), toHttpData(sse), trailers);
    }

    private static ResponseHeaders sanitizeHeaders(ResponseHeaders headers) {
        if (headers == defaultHttpHeaders) {
            return headers;
        }
        return ensureContentType(ensureHttpStatus(headers));
    }

    static ResponseHeaders ensureHttpStatus(ResponseHeaders headers) {
        final HttpStatus status = headers.status();
        if (status.equals(HttpStatus.OK)) {
            return headers;
        }

        if (!warnedStatusCode) {
            logger.warn(
                    "Overwriting the HTTP status code from '{}' to '{}' for Server-Sent Events. " +
                    "Do not set an HTTP status code on the HttpHeaders when calling factory methods in '{}', " +
                    "or set '{}' if you want to specify its status code. " +
                    "Please refer to https://www.w3.org/TR/eventsource/ for more information.",
                    status, HttpStatus.OK, ServerSentEvents.class.getSimpleName(), HttpStatus.OK);
            warnedStatusCode = true;
        }
        return headers.toBuilder().status(HttpStatus.OK).build();
    }

    static ResponseHeaders ensureContentType(ResponseHeaders headers) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return headers.toBuilder()
                          .add(HttpHeaderNames.CONTENT_TYPE, MediaType.EVENT_STREAM.toString())
                          .build();
        }

        if (contentType.is(MediaType.EVENT_STREAM)) {
            return headers;
        }

        if (!warnedContentType) {
            logger.warn("Overwriting content-type from '{}' to '{}' for Server-Sent Events. " +
                        "Do not set a content-type on the HttpHeaders when calling factory methods in '{}', " +
                        "or set '{}' if you want to specify its content-type. " +
                        "Please refer to https://www.w3.org/TR/eventsource/ for more information.",
                        contentType, MediaType.EVENT_STREAM,
                        ServerSentEvents.class.getSimpleName(), MediaType.EVENT_STREAM);
            warnedContentType = true;
        }
        return headers.toBuilder()
                      .contentType(MediaType.EVENT_STREAM)
                      .build();
    }

    private static HttpData toHttpData(ServerSentEvent sse) {
        final StringBuilder sb = new StringBuilder();

        // Write a comment first because a user might want to explain his or her event at first line.
        final String comment = sse.comment();
        if (comment != null) {
            appendField(sb, "", comment, false);
        }

        final String id = sse.id();
        if (id != null) {
            appendField(sb, "id", id, true);
        }

        final String event = sse.event();
        if (event != null) {
            appendField(sb, "event", event, true);
        }

        final String data = sse.data();
        if (data != null) {
            appendField(sb, "data", data, true);
        }

        final Duration retry = sse.retry();
        if (retry != null) {
            // Reconnection time, in milliseconds.
            sb.append("retry:").append(retry.toMillis()).append(LINE_FEED);
        }

        return sb.length() == 0 ? HttpData.empty()
                                : HttpData.ofUtf8(sb.append(LINE_FEED).toString());
    }

    private static <T> HttpData toHttpData(
            Function<? super T, ? extends ServerSentEvent> converter, T content) {
        final ServerSentEvent sse = converter.apply(content);
        return sse == null ? HttpData.empty() : toHttpData(sse);
    }

    private static void appendField(StringBuilder sb, String name, String value,
                                    boolean emitFieldForEmptyValue) {
        if (value.isEmpty()) {
            if (emitFieldForEmptyValue) {
                // Emit name only if the value is an empty string.
                sb.append(name).append(LINE_FEED);
            }
        } else {
            sb.append(name).append(':');

            final String[] values = value.split("\n");
            assert values.length > 0;
            if (values.length == 1) {
                sb.append(value);
            } else {
                final int len = values.length - 1;
                for (int i = 0; i < len; i++) {
                    sb.append(values[i]).append(LINE_FEED).append(name).append(':');
                }
                sb.append(values[len]);
            }
            sb.append(LINE_FEED);
        }
    }

    private ServerSentEvents() {}
}

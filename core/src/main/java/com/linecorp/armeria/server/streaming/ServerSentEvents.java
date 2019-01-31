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
import static com.linecorp.armeria.server.streaming.SanitizationUtil.ensureContentType;
import static com.linecorp.armeria.server.streaming.SanitizationUtil.ensureHttpStatus;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.sse.ServerSentEvent;

/**
 * A utility class which helps to create a <a href="https://www.w3.org/TR/eventsource/">Server-sent Events</a>
 * stream from a content {@link Publisher} or {@link Stream}.
 *
 * <p>A user simply creates a streaming {@link HttpResponse} which emits Server-sent Events, e.g.
 * <pre>{@code
 * Server server = new ServerBuilder()
 *         // Emit Server-sent Events with the SeverSentEvent instances published by a publisher.
 *         .service("/sse1",
 *                  (ctx, req) -> ServerSentEvents.fromPublisher(
 *                          Flux.just(ServerSentEvent.ofData("foo"), ServerSentEvent.ofData("bar"))))
 *         // Emit Server-sent Events with converting instances published by a publisher into
 *         // ServerSentEvent instances.
 *         .service("/sse2",
 *                  (ctx, req) -> ServerSentEvents.fromPublisher(
 *                          Flux.just("foo", "bar"), ServerSentEvent::ofData))
 *         .build();
 * }</pre>
 */
public final class ServerSentEvents {

    /**
     * A line feed character which marks the end of a field in Server-sent Events.
     */
    private static final char LINE_FEED = '\n';

    /**
     * A default {@link HttpHeaders} of Server-sent Events.
     */
    private static final HttpHeaders defaultHttpHeaders =
            HttpHeaders.of(HttpStatus.OK).contentType(MediaType.EVENT_STREAM).asImmutable();

    /**
     * Creates a new Server-sent Events stream from the specified {@link Publisher}.
     *
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     */
    public static HttpResponse fromPublisher(Publisher<? extends ServerSentEvent> contentPublisher) {
        return fromPublisher(defaultHttpHeaders, contentPublisher, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     */
    public static HttpResponse fromPublisher(HttpHeaders headers,
                                             Publisher<? extends ServerSentEvent> contentPublisher) {
        return fromPublisher(headers, contentPublisher, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     */
    public static HttpResponse fromPublisher(HttpHeaders headers,
                                             Publisher<? extends ServerSentEvent> contentPublisher,
                                             HttpHeaders trailingHeaders) {
        requireNonNull(headers, "headers");
        requireNonNull(contentPublisher, "contentPublisher");
        requireNonNull(trailingHeaders, "trailingHeaders");
        return streamingFrom(contentPublisher, sanitizeHeaders(headers), trailingHeaders,
                             ServerSentEvents::toHttpData);
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Publisher} and {@code converter}.
     *
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromPublisher(Publisher<T> contentPublisher,
                                                 Function<? super T, ? extends ServerSentEvent> converter) {
        return fromPublisher(defaultHttpHeaders, contentPublisher, HttpHeaders.EMPTY_HEADERS, converter);
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Publisher} and {@code converter}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromPublisher(HttpHeaders headers,
                                                 Publisher<T> contentPublisher,
                                                 Function<? super T, ? extends ServerSentEvent> converter) {
        return fromPublisher(headers, contentPublisher, HttpHeaders.EMPTY_HEADERS, converter);
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Publisher} and {@code converter}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromPublisher(HttpHeaders headers,
                                                 Publisher<T> contentPublisher,
                                                 HttpHeaders trailingHeaders,
                                                 Function<? super T, ? extends ServerSentEvent> converter) {
        requireNonNull(headers, "headers");
        requireNonNull(contentPublisher, "contentPublisher");
        requireNonNull(trailingHeaders, "trailingHeaders");
        requireNonNull(converter, "converter");
        return streamingFrom(contentPublisher, sanitizeHeaders(headers), trailingHeaders,
                             o -> toHttpData(converter, o));
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Stream}.
     *
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(Stream<? extends ServerSentEvent> contentStream,
                                          Executor executor) {
        return fromStream(defaultHttpHeaders, contentStream, HttpHeaders.EMPTY_HEADERS, executor);
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(HttpHeaders headers,
                                          Stream<? extends ServerSentEvent> contentStream,
                                          Executor executor) {
        return fromStream(headers, contentStream, HttpHeaders.EMPTY_HEADERS, executor);
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     * @param executor the executor which iterates the stream
     */
    public static HttpResponse fromStream(HttpHeaders headers,
                                          Stream<? extends ServerSentEvent> contentStream,
                                          HttpHeaders trailingHeaders, Executor executor) {
        requireNonNull(headers, "headers");
        requireNonNull(contentStream, "contentStream");
        requireNonNull(trailingHeaders, "trailingHeaders");
        requireNonNull(executor, "executor");
        return streamingFrom(contentStream, sanitizeHeaders(headers), trailingHeaders,
                             ServerSentEvents::toHttpData, executor);
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Stream} and {@code converter}.
     *
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromStream(Stream<T> contentStream, Executor executor,
                                              Function<? super T, ? extends ServerSentEvent> converter) {
        return fromStream(defaultHttpHeaders, contentStream, HttpHeaders.EMPTY_HEADERS, executor, converter);
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Stream} and {@code converter}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromStream(HttpHeaders headers, Stream<T> contentStream, Executor executor,
                                              Function<? super T, ? extends ServerSentEvent> converter) {
        return fromStream(headers, contentStream, HttpHeaders.EMPTY_HEADERS, executor, converter);
    }

    /**
     * Creates a new Server-sent Events stream from the specified {@link Stream} and {@code converter}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     * @param executor the executor which iterates the stream
     * @param converter the converter which converts published objects into {@link ServerSentEvent}s
     */
    public static <T> HttpResponse fromStream(HttpHeaders headers, Stream<T> contentStream,
                                              HttpHeaders trailingHeaders, Executor executor,
                                              Function<? super T, ? extends ServerSentEvent> converter) {
        requireNonNull(headers, "headers");
        requireNonNull(contentStream, "contentStream");
        requireNonNull(trailingHeaders, "trailingHeaders");
        requireNonNull(executor, "executor");
        requireNonNull(converter, "converter");
        return streamingFrom(contentStream, sanitizeHeaders(headers), trailingHeaders,
                             o -> toHttpData(converter, o), executor);
    }

    /**
     * Creates a new Server-sent Events stream of the specified {@code content}.
     *
     * @param content the object supposed to send as contents
     */
    public static <T extends ServerSentEvent> HttpResponse fromServerSentEvent(T content) {
        return fromServerSentEvent(defaultHttpHeaders, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new Server-sent Events stream of the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     */
    public static <T extends ServerSentEvent> HttpResponse fromServerSentEvent(HttpHeaders headers,
                                                                               T content) {
        return fromServerSentEvent(headers, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new Server-sent Events stream of the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     */
    public static <T extends ServerSentEvent> HttpResponse fromServerSentEvent(HttpHeaders headers,
                                                                               T content,
                                                                               HttpHeaders trailingHeaders) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");
        return HttpResponse.of(sanitizeHeaders(headers), toHttpData(content), trailingHeaders);
    }

    private static HttpHeaders sanitizeHeaders(HttpHeaders headers) {
        if (headers == defaultHttpHeaders) {
            return headers;
        }
        return ensureContentType(ensureHttpStatus(headers), MediaType.EVENT_STREAM);
    }

    private static <T extends ServerSentEvent> HttpData toHttpData(T sse) {
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

        final String sseText = sb.toString();
        return sseText.isEmpty() ? HttpData.EMPTY_DATA : HttpData.ofUtf8(sseText);
    }

    private static <T> HttpData toHttpData(
            Function<? super T, ? extends ServerSentEvent> converter, T content) {
        final ServerSentEvent sse = converter.apply(content);
        return sse == null ? HttpData.EMPTY_DATA : toHttpData(sse);
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

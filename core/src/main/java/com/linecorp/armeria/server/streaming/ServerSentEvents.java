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

import java.util.concurrent.Executor;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.sse.ServerSentEvent;

/**
 * A utility class which helps to create a Server-sent Event stream from a content {@link Publisher}
 * or {@link Stream}.
 *
 * @see <a href="https://www.w3.org/TR/eventsource/">Server-sent Event</a>
 */
public final class ServerSentEvents {

    private static final Logger logger = LoggerFactory.getLogger(ServerSentEvents.class);

    /**
     * A line feed character which marks the end of a field in Server-sent Events.
     */
    private static final char LINE_FEED = '\n';

    /**
     * A default {@link HttpHeaders} of Server-sent Event.
     */
    private static final HttpHeaders defaultHttpHeaders =
            HttpHeaders.of(HttpStatus.OK).contentType(MediaType.EVENT_STREAM).asImmutable();

    /**
     * Creates a new Server-sent Event stream from the specified {@link Publisher}.
     *
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     */
    public static <T> HttpResponse fromPublisher(Publisher<T> contentPublisher) {
        return fromPublisher(defaultHttpHeaders, contentPublisher, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new Server-sent Event stream from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     */
    public static <T> HttpResponse fromPublisher(HttpHeaders headers, Publisher<T> contentPublisher) {
        return fromPublisher(headers, contentPublisher, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new Server-sent Event stream from the specified {@link Publisher}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentPublisher the {@link Publisher} which publishes the objects supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     */
    public static <T> HttpResponse fromPublisher(HttpHeaders headers, Publisher<T> contentPublisher,
                                                 HttpHeaders trailingHeaders) {
        requireNonNull(headers, "headers");
        requireNonNull(contentPublisher, "contentPublisher");
        requireNonNull(trailingHeaders, "trailingHeaders");
        return streamingFrom(contentPublisher, ensureEventStreamContentType(headers), trailingHeaders,
                             ServerSentEvents::toHttpData);
    }

    /**
     * Creates a new Server-sent Event stream from the specified {@link Stream}.
     *
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static <T> HttpResponse fromStream(Stream<T> contentStream, Executor executor) {
        return fromStream(defaultHttpHeaders, contentStream, HttpHeaders.EMPTY_HEADERS, executor);
    }

    /**
     * Creates a new Server-sent Event stream from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param executor the executor which iterates the stream
     */
    public static <T> HttpResponse fromStream(HttpHeaders headers, Stream<T> contentStream,
                                              Executor executor) {
        return fromStream(headers, contentStream, HttpHeaders.EMPTY_HEADERS, executor);
    }

    /**
     * Creates a new Server-sent Event stream from the specified {@link Stream}.
     *
     * @param headers the HTTP headers supposed to send
     * @param contentStream the {@link Stream} which publishes the objects supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     * @param executor the executor which iterates the stream
     */
    public static <T> HttpResponse fromStream(HttpHeaders headers, Stream<T> contentStream,
                                              HttpHeaders trailingHeaders, Executor executor) {
        requireNonNull(headers, "headers");
        requireNonNull(contentStream, "contentStream");
        requireNonNull(trailingHeaders, "trailingHeaders");
        requireNonNull(executor, "executor");
        return streamingFrom(contentStream, ensureEventStreamContentType(headers), trailingHeaders,
                             ServerSentEvents::toHttpData, executor);
    }

    /**
     * Creates a new Server-sent Event stream of the specified {@code content}.
     *
     * @param content the object supposed to send as contents
     */
    public static <T> HttpResponse fromObject(@Nullable T content) {
        return fromObject(defaultHttpHeaders, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new Server-sent Event stream of the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     */
    public static <T> HttpResponse fromObject(HttpHeaders headers, @Nullable T content) {
        return fromObject(headers, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new Server-sent Event stream of the specified {@code content}.
     *
     * @param headers the HTTP headers supposed to send
     * @param content the object supposed to send as contents
     * @param trailingHeaders the trailing HTTP headers supposed to send
     */
    public static <T> HttpResponse fromObject(HttpHeaders headers, @Nullable T content,
                                              HttpHeaders trailingHeaders) {
        requireNonNull(headers, "headers");
        requireNonNull(trailingHeaders, "trailingHeaders");
        return HttpResponse.of(ensureEventStreamContentType(headers), toHttpData(content), trailingHeaders);
    }

    private static HttpHeaders ensureEventStreamContentType(HttpHeaders headers) {
        if (headers == defaultHttpHeaders) {
            return headers;
        }

        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return headers.toMutable().contentType(MediaType.EVENT_STREAM);
        }
        if (contentType.is(MediaType.EVENT_STREAM)) {
            return headers;
        }

        logger.warn("{} Overwrite content-type for Server-sent Events: {}",
                    RequestContext.current(), contentType);
        return headers.toMutable().contentType(MediaType.EVENT_STREAM);
    }

    private static HttpData toHttpData(@Nullable Object content) {
        if (content == null) {
            return HttpData.EMPTY_DATA;
        }

        final StringBuilder sb = new StringBuilder();
        if (content instanceof ServerSentEvent) {
            final ServerSentEvent<?> sse = (ServerSentEvent<?>) content;

            // Write a comment first because a user might want to explain his or her event at first line.
            sse.comment().ifPresent(comment -> appendField(sb, "", comment, false));

            sse.id().ifPresent(id -> appendField(sb, "id", id, true));
            sse.event().ifPresent(event -> appendField(sb, "event", event, true));
            sse.dataText().ifPresent(data -> appendField(sb, "data", data, true));

            // Reconnection time, in milliseconds.
            sse.retry().ifPresent(retry -> sb.append("retry:").append(retry.toMillis()).append(LINE_FEED));
        } else {
            appendField(sb, "data", String.valueOf(content), true);
        }

        final String event = sb.toString();
        return event.isEmpty() ? HttpData.EMPTY_DATA : HttpData.ofUtf8(event);
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

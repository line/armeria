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
package com.linecorp.armeria.internal.server;

import static com.linecorp.armeria.internal.common.util.ObjectCollectingUtil.collectFrom;
import static java.util.Objects.requireNonNull;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * A utility class which helps to send a streaming {@link HttpResponse}.
 */
public final class ResponseConversionUtil {

    /**
     * Returns a new {@link HttpResponseWriter} which has a content converted from the collected objects.
     *
     * @param stream a sequence of objects
     * @param headers to be written to the returned {@link HttpResponseWriter}
     * @param trailers to be written to the returned {@link HttpResponseWriter}
     * @param contentConverter converts the collected objects into a content of the response
     * @param executor executes the collecting job
     */
    public static HttpResponseWriter aggregateFrom(Stream<?> stream,
                                                   ResponseHeaders headers, HttpHeaders trailers,
                                                   Function<Object, HttpData> contentConverter,
                                                   Executor executor) {
        requireNonNull(stream, "stream");
        requireNonNull(headers, "headers");
        requireNonNull(trailers, "trailers");
        requireNonNull(contentConverter, "contentConverter");
        requireNonNull(executor, "executor");

        return aggregateFrom(collectFrom(stream, executor), headers, trailers, contentConverter);
    }

    /**
     * Returns a new {@link HttpResponseWriter} which has a content converted from the collected objects.
     *
     * @param publisher publishes objects
     * @param headers to be written to the returned {@link HttpResponseWriter}
     * @param trailers to be written to the returned {@link HttpResponseWriter}
     * @param contentConverter converts the collected objects into a content of the response
     */
    public static HttpResponseWriter aggregateFrom(Publisher<?> publisher,
                                                   ResponseHeaders headers, HttpHeaders trailers,
                                                   Function<Object, HttpData> contentConverter) {
        requireNonNull(publisher, "publisher");
        requireNonNull(headers, "headers");
        requireNonNull(trailers, "trailers");
        requireNonNull(contentConverter, "contentConverter");

        return aggregateFrom(collectFrom(publisher), headers, trailers, contentConverter);
    }

    private static HttpResponseWriter aggregateFrom(CompletableFuture<?> future,
                                                    ResponseHeaders headers, HttpHeaders trailers,
                                                    Function<Object, HttpData> contentConverter) {
        final HttpResponseWriter writer = HttpResponse.streaming();
        writer.whenComplete().handle((ignored, cause) -> {
            if (cause != null) {
                future.completeExceptionally(cause);
            }
            return null;
        });
        future.handle((result, cause) -> {
            if (cause != null) {
                writer.close(cause);
                return null;
            }
            try {
                final HttpData content = contentConverter.apply(result);
                writer.write(headers);
                writer.write(content);
                if (!trailers.isEmpty()) {
                    writer.write(trailers);
                }
                writer.close();
            } catch (Exception e) {
                writer.close(e);
            }
            return null;
        });
        return writer;
    }

    /**
     * Returns a new {@link HttpResponseWriter} which sends a streaming response from the specified
     * {@link Stream}.
     *
     * @param stream a sequence of objects
     * @param headers to be written to the returned {@link HttpResponseWriter}
     * @param trailers to be written to the returned {@link HttpResponseWriter}
     * @param contentConverter converts the published objects into streaming contents of the response
     * @param executor executes the iteration of the stream
     */
    public static <T> HttpResponseWriter streamingFrom(Stream<T> stream,
                                                       ResponseHeaders headers, HttpHeaders trailers,
                                                       Function<T, HttpData> contentConverter,
                                                       Executor executor) {
        requireNonNull(stream, "stream");
        requireNonNull(headers, "headers");
        requireNonNull(trailers, "trailers");
        requireNonNull(contentConverter, "contentConverter");
        requireNonNull(executor, "executor");

        final HttpResponseWriter writer = HttpResponse.streaming();
        executor.execute(() -> {
            try {
                final Iterator<T> it = stream.sequential().iterator();
                boolean headerSent = false;
                while (it.hasNext()) {
                    final HttpData content = contentConverter.apply(it.next());
                    if (!headerSent) {
                        writer.write(headers);
                        headerSent = true;
                    }
                    writer.write(content);
                }
                if (!trailers.isEmpty()) {
                    writer.write(trailers);
                }
                writer.close();
            } catch (Exception e) {
                writer.close(e);
            }
        });
        return writer;
    }

    /**
     * Returns a new {@link HttpResponseWriter} which sends a streaming response from the specified
     * {@link Publisher}.
     *
     * @param publisher publishes objects
     * @param headers to be written to the returned {@link HttpResponseWriter}
     * @param trailers to be written to the returned {@link HttpResponseWriter}
     * @param contentConverter converts the published objects into streaming contents of the response
     */
    public static <T> HttpResponseWriter streamingFrom(Publisher<T> publisher,
                                                       ResponseHeaders headers, HttpHeaders trailers,
                                                       Function<T, HttpData> contentConverter) {
        final HttpResponseWriter writer = HttpResponse.streaming();
        publisher.subscribe(new StreamingSubscriber<>(writer, headers, trailers, contentConverter));
        return writer;
    }

    /**
     * A {@link Subscriber} implementation which writes a streaming response with the contents converted from
     * the objects published from a publisher.
     */
    private static final class StreamingSubscriber<T> implements Subscriber<T> {

        private final HttpResponseWriter writer;
        private final ResponseHeaders headers;
        private final HttpHeaders trailers;
        private final Function<T, HttpData> contentConverter;
        @Nullable
        private Subscription subscription;
        private boolean headersSent;

        StreamingSubscriber(HttpResponseWriter writer,
                            ResponseHeaders headers, HttpHeaders trailers,
                            Function<T, HttpData> contentConverter) {
            this.writer = requireNonNull(writer, "writer");
            this.headers = requireNonNull(headers, "headers");
            this.trailers = requireNonNull(trailers, "trailers");
            this.contentConverter = requireNonNull(contentConverter, "contentConverter");
        }

        @Override
        public void onSubscribe(Subscription s) {
            assert subscription == null;
            subscription = s;
            writer.whenComplete().handle((unused, cause) -> {
                if (cause != null) {
                    s.cancel();
                }
                return null;
            });

            s.request(1);
        }

        @Override
        public void onNext(T value) {
            if (!writer.isOpen()) {
                return;
            }
            try {
                // To get an exception from the converter before sending the headers.
                final HttpData content = contentConverter.apply(value);
                if (!headersSent) {
                    writer.write(headers);
                    headersSent = true;
                }
                writer.write(content);
                writer.whenConsumed().thenRun(() -> {
                    assert subscription != null;
                    subscription.request(1);
                });
            } catch (Exception e) {
                try {
                    writer.close(e);
                } finally {
                    assert subscription != null;
                    subscription.cancel();
                }
            }
        }

        @Override
        public void onError(Throwable cause) {
            if (!writer.isOpen()) {
                return;
            }
            writer.close(cause);
        }

        @Override
        public void onComplete() {
            if (!writer.isOpen()) {
                return;
            }
            if (!trailers.isEmpty()) {
                if (!writer.tryWrite(trailers)) {
                    return;
                }
            }
            writer.close();
        }
    }

    private ResponseConversionUtil() {}
}

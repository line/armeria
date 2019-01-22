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
package com.linecorp.armeria.internal;

import static com.linecorp.armeria.internal.ObjectCollectingUtil.collectFrom;
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

/**
 * A utility class which helps to send a streaming {@link HttpResponse}.
 */
public final class ResponseConversionUtil {

    /**
     * Returns a new {@link HttpResponseWriter} which has a content converted from the collected objects.
     *
     * @param stream a sequence of objects
     * @param headers to be written to the returned {@link HttpResponseWriter}
     * @param trailingHeaders to be written to the returned {@link HttpResponseWriter}
     * @param contentConverter converts the collected objects into a content of the response
     * @param executor executes the collecting job
     */
    public static HttpResponseWriter aggregateFrom(Stream<?> stream,
                                                   HttpHeaders headers, HttpHeaders trailingHeaders,
                                                   Function<Object, HttpData> contentConverter,
                                                   Executor executor) {
        requireNonNull(stream, "stream");
        requireNonNull(headers, "headers");
        requireNonNull(trailingHeaders, "trailingHeaders");
        requireNonNull(contentConverter, "contentConverter");
        requireNonNull(executor, "executor");

        return aggregateFrom(collectFrom(stream, executor),
                             headers, trailingHeaders, contentConverter);
    }

    /**
     * Returns a new {@link HttpResponseWriter} which has a content converted from the collected objects.
     *
     * @param publisher publishes objects
     * @param headers to be written to the returned {@link HttpResponseWriter}
     * @param trailingHeaders to be written to the returned {@link HttpResponseWriter}
     * @param contentConverter converts the collected objects into a content of the response
     */
    public static HttpResponseWriter aggregateFrom(Publisher<?> publisher,
                                                   HttpHeaders headers, HttpHeaders trailingHeaders,
                                                   Function<Object, HttpData> contentConverter) {
        requireNonNull(publisher, "publisher");
        requireNonNull(headers, "headers");
        requireNonNull(trailingHeaders, "trailingHeaders");
        requireNonNull(contentConverter, "contentConverter");

        return aggregateFrom(collectFrom(publisher),
                             headers, trailingHeaders, contentConverter);
    }

    private static HttpResponseWriter aggregateFrom(CompletableFuture<?> future,
                                                    HttpHeaders headers, HttpHeaders trailingHeaders,
                                                    Function<Object, HttpData> contentConverter) {
        final HttpResponseWriter writer = HttpResponse.streaming();
        future.handle((result, cause) -> {
            if (cause != null) {
                writer.close(cause);
                return null;
            }
            try {
                final HttpData content = contentConverter.apply(result);
                writer.write(headers);
                writer.write(content);
                if (!trailingHeaders.isEmpty()) {
                    writer.write(trailingHeaders);
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
     * @param trailingHeaders to be written to the returned {@link HttpResponseWriter}
     * @param contentConverter converts the published objects into streaming contents of the response
     * @param executor executes the iteration of the stream
     */
    public static <T> HttpResponseWriter streamingFrom(Stream<T> stream,
                                                       HttpHeaders headers, HttpHeaders trailingHeaders,
                                                       Function<T, HttpData> contentConverter,
                                                       Executor executor) {
        requireNonNull(stream, "stream");
        requireNonNull(headers, "headers");
        requireNonNull(trailingHeaders, "trailingHeaders");
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
                if (!trailingHeaders.isEmpty()) {
                    writer.write(trailingHeaders);
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
     * @param trailingHeaders to be written to the returned {@link HttpResponseWriter}
     * @param contentConverter converts the published objects into streaming contents of the response
     */
    public static <T> HttpResponseWriter streamingFrom(Publisher<T> publisher,
                                                       HttpHeaders headers, HttpHeaders trailingHeaders,
                                                       Function<T, HttpData> contentConverter) {
        final HttpResponseWriter writer = HttpResponse.streaming();
        publisher.subscribe(new StreamingSubscriber<>(writer, headers, trailingHeaders, contentConverter));
        return writer;
    }

    /**
     * A {@link Subscriber} implementation which writes a streaming response with the contents converted from
     * the objects published from a publisher.
     */
    private static final class StreamingSubscriber<T> implements Subscriber<T> {

        private final HttpResponseWriter writer;
        private final HttpHeaders headers;
        private final HttpHeaders trailingHeaders;
        private final Function<T, HttpData> contentConverter;
        @Nullable
        private Subscription subscription;
        private boolean headersSent;

        StreamingSubscriber(HttpResponseWriter writer,
                            HttpHeaders headers, HttpHeaders trailingHeaders,
                            Function<T, HttpData> contentConverter) {
            this.writer = requireNonNull(writer, "writer");
            this.headers = requireNonNull(headers, "headers");
            this.trailingHeaders = requireNonNull(trailingHeaders, "trailingHeaders");
            this.contentConverter = requireNonNull(contentConverter, "contentConverter");
        }

        @Override
        public void onSubscribe(Subscription s) {
            assert subscription == null;
            subscription = s;
            writer.closeFuture().handle((unused, cause) -> {
                if (cause != null) {
                    s.cancel();
                }
                return null;
            });
            try {
                writer.onDemand(new Runnable() {
                    @Override
                    public void run() {
                        s.request(1);
                        writer.onDemand(this);
                    }
                });
            } catch (Exception e) {
                onError(e);
            }
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
            } catch (Exception e) {
                onError(e);
            }
        }

        @Override
        public void onError(Throwable cause) {
            if (!writer.isOpen()) {
                return;
            }
            try {
                writer.close(cause);
            } catch (Exception e) {
                // 'subscription.cancel()' would be called by the close future listener of the writer,
                // so we call it when we failed to close the writer.
                assert subscription != null;
                subscription.cancel();
            }
        }

        @Override
        public void onComplete() {
            if (!writer.isOpen()) {
                return;
            }
            if (!trailingHeaders.isEmpty()) {
                writer.write(trailingHeaders);
            }
            writer.close();
        }
    }

    private ResponseConversionUtil() {}
}

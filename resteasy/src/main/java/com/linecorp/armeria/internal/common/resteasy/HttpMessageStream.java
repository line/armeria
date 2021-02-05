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

package com.linecorp.armeria.internal.common.resteasy;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.concurrent.EventExecutor;

/**
 * A utility class to handle {@link HttpMessageStream} processing for {@link HttpRequest} and
 * {@link HttpResponse}.
 */
@UnstableApi
public final class HttpMessageStream {

    public static HttpMessageStream of(HttpRequest request, Duration timeout, EventExecutor executor) {
        final HttpMessageStream message = new HttpMessageStream(request.headers(), timeout);
        request.subscribe(new HttpMessageSubscriberAdapter(message.asSubscriber()), executor);
        return message;
    }

    public static HttpMessageStream of(HttpRequest request, Duration timeout) {
        final HttpMessageStream message = new HttpMessageStream(request.headers(), timeout);
        request.subscribe(new HttpMessageSubscriberAdapter(message.asSubscriber()));
        return message;
    }

    public static HttpMessageStream of(HttpRequest request, EventExecutor executor) {
        final HttpMessageStream message = new HttpMessageStream(request.headers());
        request.subscribe(new HttpMessageSubscriberAdapter(message.asSubscriber()), executor);
        return message;
    }

    public static HttpMessageStream of(HttpRequest request) {
        final HttpMessageStream message = new HttpMessageStream(request.headers());
        request.subscribe(new HttpMessageSubscriberAdapter(message.asSubscriber()));
        return message;
    }

    public static HttpMessageStream of(HttpResponse response, Duration timeout, EventExecutor executor) {
        final HttpMessageStream message = new HttpMessageStream(timeout);
        response.subscribe(new HttpMessageSubscriberAdapter(message.asSubscriber()), executor);
        return message;
    }

    public static HttpMessageStream of(HttpResponse response, Duration timeout) {
        final HttpMessageStream message = new HttpMessageStream(timeout);
        response.subscribe(new HttpMessageSubscriberAdapter(message.asSubscriber()));
        return message;
    }

    public static HttpMessageStream of(HttpResponse response, EventExecutor executor) {
        final HttpMessageStream message = new HttpMessageStream();
        response.subscribe(new HttpMessageSubscriberAdapter(message.asSubscriber()), executor);
        return message;
    }

    public static HttpMessageStream of(HttpResponse response) {
        final HttpMessageStream message = new HttpMessageStream();
        response.subscribe(new HttpMessageSubscriberAdapter(message.asSubscriber()));
        return message;
    }

    private final CompletableFuture<HttpHeaders> headersFuture = new CompletableFuture<>();
    private final HttpHeadersBuilder headersBuilder = HttpHeaders.builder();
    private final ByteBuffersBackedInputStream content;

    private HttpMessageStream(HttpHeaders headers, Duration timeout) {
        headersBuilder.add(requireNonNull(headers, "headers"));
        headersFuture.complete(headers);
        content = new ByteBuffersBackedInputStream(timeout);
    }

    private HttpMessageStream(HttpHeaders headers) {
        headersBuilder.add(headers);
        headersFuture.complete(headers);
        content = new ByteBuffersBackedInputStream();
    }

    private HttpMessageStream(Duration timeout) {
        content = new ByteBuffersBackedInputStream(timeout);
    }

    private HttpMessageStream() {
        content = new ByteBuffersBackedInputStream();
    }

    /**
     * Returns a snapshot of the available headers.
     */
    public HttpHeaders headers() {
        return headersBuilder.build();
    }

    public CompletableFuture<HttpHeaders> awaitHeaders() {
        return headersFuture;
    }

    public InputStream content() {
        return content;
    }

    public boolean isEos() {
        return content.isEos();
    }

    private HttpMessageSubscriber asSubscriber() {
        return new HttpMessageSubscriber() {
            @Override
            public void onData(HttpData data) {
                content.add(data.byteBuf());
            }

            @Override
            public void onHeaders(HttpHeaders headers) {
                headersBuilder.add(headers);
                if (!headersFuture.isDone()) {
                    headersFuture.complete(headers());
                }
            }

            @Override
            public void onError(Throwable cause) {
                content.interrupt(cause);
                if (!headersFuture.isDone()) {
                    headersFuture.complete(headers());
                }
            }

            @Override
            public void onComplete() {
                content.setEos(); // signal the end of stream
                if (!headersFuture.isDone()) {
                    headersFuture.complete(headers());
                }
            }
        };
    }
}

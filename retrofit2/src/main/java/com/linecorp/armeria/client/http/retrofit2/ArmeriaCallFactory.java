/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.http.retrofit2;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpResponse;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * A {@link Call.Factory} that creates a {@link Call} instance for {@link HttpClient}.
 */
public class ArmeriaCallFactory implements Call.Factory {

    private final HttpClient httpClient;

    /**
     * Creates a {@link Call.Factory} using the specified {@link HttpClient} instance.
     * @param httpClient The {@link HttpClient} instance to be used.
     */
    public ArmeriaCallFactory(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Call newCall(Request request) {
        return new ArmeriaCall(this, request);
    }

    private static class ArmeriaCall implements Call {

        private final ArmeriaCallFactory callFactory;
        private final Request request;

        // Guarded by this.
        private boolean executed;

        volatile boolean canceled;

        private CompletableFuture<AggregatedHttpMessage> future;

        ArmeriaCall(ArmeriaCallFactory callFactory, Request request) {
            this.callFactory = callFactory;
            this.request = request;
        }

        private static HttpResponse doCall(HttpClient httpClient, Request request) {
            URL url = request.url().url();
            StringBuilder uriBuilder = new StringBuilder(url.getPath());
            if (url.getQuery() != null) {
                uriBuilder.append('?').append(url.getQuery());
            }
            String uri = uriBuilder.toString();
            final HttpHeaders headers;
            switch (request.method()) {
                case "GET":
                    headers = HttpHeaders.of(HttpMethod.GET, uri);
                    break;
                case "HEAD":
                    headers = HttpHeaders.of(HttpMethod.HEAD, uri);
                    break;
                case "POST":
                    headers = HttpHeaders.of(HttpMethod.POST, uri);
                    break;
                case "DELETE":
                    headers = HttpHeaders.of(HttpMethod.DELETE, uri);
                    break;
                case "PUT":
                    headers = HttpHeaders.of(HttpMethod.PUT, uri);
                    break;
                case "PATCH":
                    headers = HttpHeaders.of(HttpMethod.PATCH, uri);
                    break;
                case "OPTIONS":
                    headers = HttpHeaders.of(HttpMethod.OPTIONS, uri);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid HTTP method:" + request.method());
            }
            request.headers().toMultimap().forEach(
                    (key, values) -> headers.add(HttpHeaderNames.of(key), values));
            if (request.body() != null) {
                headers.set(HttpHeaderNames.CONTENT_TYPE, request.body().contentType().toString());
                final BufferSinkHttpData contentBuffer;
                try {
                    contentBuffer = new BufferSinkHttpData((int) request.body().contentLength());
                    request.body().writeTo(contentBuffer);
                } catch (IOException e) {
                    throw new IllegalArgumentException(
                            "Failed to convert RequestBody to HttpData. " + request.method(), e);
                }
                return httpClient.execute(headers, contentBuffer);
            }
            return httpClient.execute(headers);
        }

        @Override
        public Request request() {
            return request;
        }

        private synchronized CompletableFuture<AggregatedHttpMessage> createRequest() {
            if (executed) {
                throw new IllegalStateException("Already Executed");
            }
            final CompletableFuture<AggregatedHttpMessage> future = doCall(callFactory.httpClient, request)
                    .aggregate();
            executed = true;
            return future;
        }

        @Override
        public Response execute() throws IOException {
            future = createRequest();
            try {
                return convertResponse(future.get());
            } catch (InterruptedException e) {
                throw new IOException(e);
            } catch (ExecutionException e) {
                throw new IOException(e.getCause());
            }
        }

        @Override
        public void enqueue(Callback responseCallback) {
            future = createRequest();
            future.whenComplete((response, throwable) -> {
                if (throwable != null) {
                    responseCallback.onFailure(this, new IOException(throwable.getMessage(), throwable));
                } else {
                    if (!canceled) {
                        try {
                            responseCallback.onResponse(this, convertResponse(response));
                        } catch (IOException e) {
                            responseCallback.onFailure(this, e);
                        }
                    } else {
                        responseCallback.onFailure(this, new IOException("Canceled"));
                    }
                }
            });
        }

        @Override
        public void cancel() {
            canceled = true;
            if (future != null) {
                future.cancel(true);
            }
        }

        @Override
        public synchronized boolean isExecuted() {
            return executed;
        }

        @Override
        public boolean isCanceled() {
            return canceled;
        }

        private Response convertResponse(AggregatedHttpMessage httpMessage) {
            Response.Builder builder = new Response.Builder();
            String contentType = httpMessage.headers().get(HttpHeaderNames.CONTENT_TYPE);
            httpMessage.headers().forEach(header -> {
                builder.addHeader(header.getKey().toString(), header.getValue());
            });
            builder.request(request);
            builder.code(httpMessage.status().code());
            builder.message(httpMessage.status().reasonPhrase());
            builder.protocol(Protocol.HTTP_1_1);
            builder.body(ResponseBody.create(
                    Strings.isNullOrEmpty(contentType) ? null : MediaType.parse(contentType),
                    httpMessage.content().array()));
            return builder.build();
        }
    }
}

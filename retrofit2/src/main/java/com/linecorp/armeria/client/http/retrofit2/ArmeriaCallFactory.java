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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpResponse;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

/**
 * A {@link Call.Factory} that creates a {@link Call} instance for {@link HttpClient}.
 */
public class ArmeriaCallFactory implements Call.Factory {

    private final HttpClient httpClient;

    /**
     * Creates a {@link Call.Factory} using the specified {@link HttpClient} instance.
     *
     * @param httpClient The {@link HttpClient} instance to be used.
     */
    public ArmeriaCallFactory(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Call newCall(Request request) {
        return new ArmeriaCall(this, request);
    }

    static class ArmeriaCall implements Call {

        private enum ExecutionState {
            IDLE, RUNNING, CANCELED, FINISHED
        }

        private static final AtomicReferenceFieldUpdater<ArmeriaCall, ExecutionState> executionStateUpdater =
                AtomicReferenceFieldUpdater.newUpdater(ArmeriaCall.class, ExecutionState.class,
                                                       "executionState");

        private final ArmeriaCallFactory callFactory;

        private final Request request;

        private volatile HttpResponse httpResponse;

        private volatile ExecutionState executionState = ExecutionState.IDLE;

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
            final RequestBody body = request.body();
            if (body != null) {
                final MediaType contentType = body.contentType();
                if (contentType != null) {
                    headers.set(HttpHeaderNames.CONTENT_TYPE, contentType.toString());
                }

                try (Buffer contentBuffer = new Buffer()) {
                    body.writeTo(contentBuffer);

                    return httpClient.execute(headers, contentBuffer.readByteArray());
                } catch (IOException e) {
                    throw new IllegalArgumentException(
                            "Failed to convert RequestBody to HttpData. " + request.method(), e);
                }
            }
            return httpClient.execute(headers);
        }

        @Override
        public Request request() {
            return request;
        }

        private synchronized void createRequest() {
            if (httpResponse != null) {
                throw new IllegalStateException("executed already");
            }
            executionStateUpdater.compareAndSet(this, ExecutionState.IDLE, ExecutionState.RUNNING);
            httpResponse = doCall(callFactory.httpClient, request);
        }

        @Override
        public Response execute() throws IOException {
            CompletableCallback completableCallback = new CompletableCallback();
            enqueue(completableCallback);
            try {
                return completableCallback.join();
            } catch (CancellationException e) {
                throw new IOException(e);
            } catch (CompletionException e) {
                throw new IOException(e.getCause());
            }
        }

        @Override
        public void enqueue(Callback callback) {
            createRequest();
            httpResponse.subscribe(new ArmeriaCallSubscriber(this, callback, request));
        }

        @Override
        public void cancel() {
            executionStateUpdater.set(this, ExecutionState.CANCELED);
        }

        @Override
        public boolean isExecuted() {
            return httpResponse != null;
        }

        @Override
        public boolean isCanceled() {
            return executionState == ExecutionState.CANCELED;
        }

        boolean tryFinish() {
            return executionStateUpdater.compareAndSet(this,
                                                       ExecutionState.IDLE,
                                                       ExecutionState.FINISHED) ||
                   executionStateUpdater.compareAndSet(this,
                                                       ExecutionState.RUNNING,
                                                       ExecutionState.FINISHED);
        }
    }
}

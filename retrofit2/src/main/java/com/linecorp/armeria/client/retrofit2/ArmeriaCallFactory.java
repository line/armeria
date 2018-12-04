/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.client.retrofit2;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;

import okhttp3.Call;
import okhttp3.Call.Factory;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.Timeout;

/**
 * A {@link Factory} that creates a {@link Call} instance for {@link HttpClient}.
 */
final class ArmeriaCallFactory implements Factory {

    static final String GROUP_PREFIX = "group_";
    private static final Pattern GROUP_PREFIX_MATCHER = Pattern.compile(GROUP_PREFIX);
    private final Map<String, HttpClient> httpClients = new ConcurrentHashMap<>();
    private final HttpClient baseHttpClient;
    private final ClientFactory clientFactory;
    private final BiFunction<String, ? super ClientOptionsBuilder, ClientOptionsBuilder> configurator;
    private final String baseAuthority;
    private final SubscriberFactory subscriberFactory;

    ArmeriaCallFactory(HttpClient baseHttpClient,
                       ClientFactory clientFactory,
                       BiFunction<String, ? super ClientOptionsBuilder, ClientOptionsBuilder> configurator,
                       SubscriberFactory subscriberFactory) {
        this.baseHttpClient = baseHttpClient;
        this.clientFactory = clientFactory;
        this.configurator = configurator;
        this.subscriberFactory = subscriberFactory;
        baseAuthority = baseHttpClient.uri().getAuthority();
        httpClients.put(baseAuthority, baseHttpClient);
    }

    @Override
    public Call newCall(Request request) {
        return new ArmeriaCall(this, request);
    }

    private static boolean isGroup(String authority) {
        return authority.startsWith(GROUP_PREFIX);
    }

    private HttpClient getHttpClient(String authority, String sessionProtocol) {
        if (baseAuthority.equals(authority)) {
            return baseHttpClient;
        }
        return httpClients.computeIfAbsent(authority, key -> {
            final String finalAuthority = isGroup(key) ?
                                          GROUP_PREFIX_MATCHER.matcher(key).replaceFirst("group:") : key;
            final String uriText = sessionProtocol + "://" + finalAuthority;
            return HttpClient.of(
                    clientFactory, uriText, configurator.apply(uriText, new ClientOptionsBuilder()).build());
        });
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

        @Nullable
        private volatile HttpResponse httpResponse;

        @SuppressWarnings("FieldMayBeFinal")
        private volatile ExecutionState executionState = ExecutionState.IDLE;

        ArmeriaCall(ArmeriaCallFactory callFactory, Request request) {
            this.callFactory = callFactory;
            this.request = request;
        }

        private static HttpResponse doCall(ArmeriaCallFactory callFactory, Request request) {
            final HttpUrl httpUrl = request.url();
            final URI uri = httpUrl.uri();
            final HttpClient httpClient = callFactory.getHttpClient(uri.getAuthority(), uri.getScheme());
            final String uriString;
            if (uri.getQuery() == null) {
                uriString = httpUrl.encodedPath();
            } else {
                uriString = httpUrl.encodedPath() + '?' + httpUrl.encodedQuery();
            }
            final HttpHeaders headers;
            switch (request.method()) {
                case "GET":
                    headers = HttpHeaders.of(HttpMethod.GET, uriString);
                    break;
                case "HEAD":
                    headers = HttpHeaders.of(HttpMethod.HEAD, uriString);
                    break;
                case "POST":
                    headers = HttpHeaders.of(HttpMethod.POST, uriString);
                    break;
                case "DELETE":
                    headers = HttpHeaders.of(HttpMethod.DELETE, uriString);
                    break;
                case "PUT":
                    headers = HttpHeaders.of(HttpMethod.PUT, uriString);
                    break;
                case "PATCH":
                    headers = HttpHeaders.of(HttpMethod.PATCH, uriString);
                    break;
                case "OPTIONS":
                    headers = HttpHeaders.of(HttpMethod.OPTIONS, uriString);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid HTTP method:" + request.method());
            }
            final Headers requestHeaders = request.headers();
            final int numHeaders = requestHeaders.size();
            for (int i = 0; i < numHeaders; i++) {
                headers.add(HttpHeaderNames.of(requestHeaders.name(i)),
                            requestHeaders.value(i));
            }
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
            httpResponse = doCall(callFactory, request);
        }

        @Override
        public Response execute() throws IOException {
            final CompletableCallback completableCallback = new CompletableCallback();
            enqueue(completableCallback);
            try {
                return completableCallback.join();
            } catch (CancellationException e) {
                throw new IOException(e);
            } catch (CompletionException e) {
                throw new IOException(Exceptions.peel(e));
            }
        }

        @Override
        public void enqueue(Callback callback) {
            createRequest();
            httpResponse.subscribe(callFactory.subscriberFactory.create(this, callback, request));
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

        @Override
        public Timeout timeout() {
            return Timeout.NONE;
        }

        boolean tryFinish() {
            return executionStateUpdater.compareAndSet(this,
                                                       ExecutionState.IDLE,
                                                       ExecutionState.FINISHED) ||
                   executionStateUpdater.compareAndSet(this,
                                                       ExecutionState.RUNNING,
                                                       ExecutionState.FINISHED);
        }

        @Override
        public Call clone() {
            return new ArmeriaCall(callFactory, request);
        }
    }
}

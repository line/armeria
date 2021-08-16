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

import static com.linecorp.armeria.client.Clients.withContextCustomizer;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;

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
import retrofit2.Invocation;

/**
 * A {@link Factory} that creates a {@link Call} instance for {@link WebClient}.
 */
final class ArmeriaCallFactory implements Factory {

    private final String baseWebClientHost;
    private final int baseWebClientPort;
    private final WebClient baseWebClient;
    private final SubscriberFactory subscriberFactory;
    private final BiFunction<SessionProtocol, Endpoint, WebClient> nonBaseWebClientFactory;

    ArmeriaCallFactory(String baseWebClientHost, int baseWebClientPort, WebClient baseWebClient,
                       SubscriberFactory subscriberFactory,
                       BiFunction<? super SessionProtocol, ? super Endpoint,
                               ? extends WebClient> nonBaseWebClientFactory) {

        this.baseWebClientHost = baseWebClientHost;
        this.baseWebClientPort = baseWebClientPort;
        this.baseWebClient = baseWebClient;
        this.subscriberFactory = subscriberFactory;

        @SuppressWarnings("unchecked")
        final BiFunction<SessionProtocol, Endpoint, WebClient> castNonBaseWebClientFactory =
                (BiFunction<SessionProtocol, Endpoint, WebClient>) nonBaseWebClientFactory;
        this.nonBaseWebClientFactory = castNonBaseWebClientFactory;
    }

    @Override
    public Call newCall(Request request) {
        return new ArmeriaCall(this, request);
    }

    WebClient getWebClient(HttpUrl url) {
        if (baseWebClient.scheme().sessionProtocol().isTls() == url.isHttps() &&
            baseWebClientHost.equals(url.host()) &&
            baseWebClientPort == url.port()) {

            return baseWebClient;
        }

        final SessionProtocol protocol = url.isHttps() ? SessionProtocol.HTTPS : SessionProtocol.HTTP;
        final Endpoint endpoint = Endpoint.unsafeCreate(url.host(), url.port());
        return nonBaseWebClientFactory.apply(protocol, endpoint);
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
            final WebClient webClient = callFactory.getWebClient(httpUrl);
            final String absolutePathRef;
            if (httpUrl.encodedQuery() == null) {
                absolutePathRef = httpUrl.encodedPath();
            } else {
                absolutePathRef = httpUrl.encodedPath() + '?' + httpUrl.encodedQuery();
            }

            final RequestHeadersBuilder headers = RequestHeaders.builder(HttpMethod.valueOf(request.method()),
                                                                         absolutePathRef);
            final Headers requestHeaders = request.headers();
            final int numHeaders = requestHeaders.size();
            for (int i = 0; i < numHeaders; i++) {
                headers.add(HttpHeaderNames.of(requestHeaders.name(i)),
                            requestHeaders.value(i));
            }

            final RequestBody body = request.body();
            final Invocation invocation = request.tag(Invocation.class);
            if (body == null) {
                // Without a body.
                try (SafeCloseable ignored = withContextCustomizer(
                        ctx -> InvocationUtil.setInvocation(ctx, invocation))) {
                    return webClient.execute(headers.build());
                }
            }

            // With a body.
            final MediaType contentType = body.contentType();
            if (contentType != null) {
                headers.set(HttpHeaderNames.CONTENT_TYPE, contentType.toString());
            }

            try (Buffer contentBuffer = new Buffer()) {
                body.writeTo(contentBuffer);

                try (SafeCloseable ignored = withContextCustomizer(
                        ctx -> InvocationUtil.setInvocation(ctx, invocation))) {
                    return webClient.execute(headers.build(), contentBuffer.readByteArray());
                }
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Failed to convert RequestBody to HttpData. " + request.method(), e);
            }
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

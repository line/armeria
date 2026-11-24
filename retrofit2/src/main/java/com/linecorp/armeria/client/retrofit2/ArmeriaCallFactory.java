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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.client.Clients.withContextCustomizer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.RequestOptionsBuilder;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.client.RequestCancellation;

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

        private final RequestCancellation cancellation = new RequestCancellation();

        private volatile boolean executed;

        @SuppressWarnings("FieldMayBeFinal")
        private volatile ExecutionState executionState = ExecutionState.IDLE;

        ArmeriaCall(ArmeriaCallFactory callFactory, Request request) {
            this.callFactory = callFactory;
            this.request = request;
        }

        private HttpResponse doCall(boolean interruptible) throws IOException {
            final Throwable cancellationCause = cancellationCause(interruptible);
            if (cancellationCause != null) {
                return HttpResponse.ofFailure(cancellationCause);
            }
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

            final RequestOptionsBuilder optionsBuilder =
                    RequestOptions.builder().exchangeType(ExchangeType.RESPONSE_STREAMING);
            cancellation.attach(optionsBuilder);
            final RequestOptions options = optionsBuilder.build();
            final RequestBody body = request.body();
            final Invocation invocation = request.tag(Invocation.class);
            if (body == null) {
                final Throwable dispatchCancellationCause = cancellationCause(interruptible);
                if (dispatchCancellationCause != null) {
                    return HttpResponse.ofFailure(dispatchCancellationCause);
                }
                // Without a body.
                try (SafeCloseable ignored = withContextCustomizer(
                        ctx -> customizeContext(ctx, invocation))) {
                    return webClient.execute(HttpRequest.of(headers.build()), options);
                }
            }

            // With a body.
            final MediaType contentType = body.contentType();
            if (contentType != null) {
                headers.set(HttpHeaderNames.CONTENT_TYPE, contentType.toString());
            }

            try (Buffer contentBuffer = new Buffer()) {
                body.writeTo(contentBuffer);
                final Throwable dispatchCancellationCause = cancellationCause(interruptible);
                if (dispatchCancellationCause != null) {
                    return HttpResponse.ofFailure(dispatchCancellationCause);
                }

                try (SafeCloseable ignored = withContextCustomizer(
                        ctx -> customizeContext(ctx, invocation))) {
                    return webClient.execute(HttpRequest.of(headers.build(),
                                                            HttpData.wrap(contentBuffer.readByteArray())),
                                             options);
                }
            }
        }

        @Nullable
        private Throwable cancellationCause(boolean interruptible) {
            final Throwable cause = cancellation.cause();
            if (cause != null) {
                return cause;
            }
            return interruptible && Thread.currentThread().isInterrupted() ? new InterruptedException() : null;
        }

        private void customizeContext(ClientRequestContext ctx, @Nullable Invocation invocation) {
            if (RequestCancellation.get(ctx) != cancellation) {
                return;
            }
            InvocationUtil.setInvocation(ctx, invocation);
            cancellation.onCancel(ctx::cancel, ctx.log().whenComplete());
        }

        private HttpResponse ownResponse(HttpResponse response) {
            cancellation.onCancel(response::abort, response.whenComplete());
            return response;
        }

        @Override
        public Request request() {
            return request;
        }

        private synchronized void claimExecution() {
            checkState(!executed, "executed already");
            // Prevent another execution after an encoding failure or interruption.
            executed = true;
            executionStateUpdater.compareAndSet(this, ExecutionState.IDLE, ExecutionState.RUNNING);
        }

        @Override
        public Response execute() throws IOException {
            claimExecution();
            final CompletableCallback callback = new CompletableCallback();
            HttpResponse response = null;
            try {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                response = ownResponse(doCall(true));
                response.subscribe(callFactory.subscriberFactory.create(this, callback, request));
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                final Response result = callback.get();
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                return result;
            } catch (InterruptedException e) {
                throw cancelOnInterrupt(e, callback, response);
            } catch (IOException e) {
                if (isEncodingInterrupted(e)) {
                    throw cancelOnInterrupt(e, callback, response);
                }
                throw new IllegalArgumentException(
                        "Failed to convert RequestBody to HttpData. " + request.method(), e);
            } catch (CancellationException e) {
                throw new IOException(e);
            } catch (ExecutionException e) {
                throw new IOException(Exceptions.peel(e));
            }
        }

        private static boolean isEncodingInterrupted(IOException cause) {
            if (Thread.currentThread().isInterrupted()) {
                return true;
            }
            final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable current = cause; current != null && seen.add(current);
                 current = current.getCause()) {
                if (current instanceof InterruptedException) {
                    return true;
                }
            }
            return false;
        }

        private InterruptedIOException cancelOnInterrupt(Throwable cause, CompletableCallback callback,
                                                         @Nullable HttpResponse response) {
            final InterruptedIOException interrupted = new InterruptedIOException("interrupted");
            interrupted.initCause(cause);
            cancel(interrupted);
            try {
                callback.abandon(interrupted);
            } finally {
                try {
                    if (response != null) {
                        response.abort(interrupted);
                    }
                } finally {
                    Thread.currentThread().interrupt();
                }
            }
            return interrupted;
        }

        @Override
        public void enqueue(Callback callback) {
            claimExecution();
            final HttpResponse response;
            try {
                response = ownResponse(doCall(false));
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Failed to convert RequestBody to HttpData. " + request.method(), e);
            }
            response.subscribe(callFactory.subscriberFactory.create(this, callback, request));
        }

        @Override
        public void cancel() {
            cancel(new IOException("cancelled"));
        }

        private void cancel(Throwable cause) {
            executionStateUpdater.set(this, ExecutionState.CANCELED);
            cancellation.cancel(cause);
        }

        @Override
        public boolean isExecuted() {
            return executed;
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

/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.BufferedSource;
import retrofit2.Retrofit;

class ArmeriaCallInterruptTest {

    private static final AtomicInteger nextId = new AtomicInteger();
    private static final Map<String, Exchange> exchanges = new ConcurrentHashMap<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/healthy", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.serviceUnder("/redirect", (ctx, req) -> {
                final String path = req.path().substring("/redirect".length());
                final Exchange exchange = exchanges.get(path);
                assertThat(exchange).isNotNull();
                exchange.redirected.complete(null);
                return HttpResponse.ofRedirect(path);
            });
            sb.serviceUnder("/held", (ctx, req) -> {
                final Exchange exchange = exchanges.get(req.path());
                assertThat(exchange).isNotNull();
                exchange.arrived.complete(ctx);
                return exchange.response;
            });
        }
    };

    @ParameterizedTest
    @CsvSource({ "H1C, false, false", "H2C, false, false", "H2C, false, true",
                 "H2C, true, false", "H2C, true, true" })
    void interruptionAbortsTransportBeforeServerResponseIsReleased(SessionProtocol protocol,
                                                                  boolean retry, boolean partialBody)
            throws Exception {
        try (Exchange exchange = new Exchange(protocol, retry, false)) {
            final Call call = exchange.call.clone();
            final Execution execution = new Execution(call, false);
            final ServiceRequestContext serverCtx = exchange.arrived.get(5, TimeUnit.SECONDS);
            final ClientRequestContext ctx = exchange.clientContext.get(5, TimeUnit.SECONDS);
            if (partialBody) {
                exchange.response.write(ResponseHeaders.of(HttpStatus.OK));
                exchange.response.write(HttpData.ofUtf8("partial"));
                ctx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).get(5, TimeUnit.SECONDS);
                ctx.eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
            }
            execution.thread.interrupt();
            assertThat(execution.done.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class)
                                                             .hasCauseInstanceOf(InterruptedException.class);
            assertThat(execution.interrupted.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(call.isCanceled()).isTrue();
            assertThat(call.isExecuted()).isTrue();
            assertThat(exchange.transportDone.get(5, TimeUnit.SECONDS)).isNotNull();
            serverCtx.whenRequestCancelling().get(5, TimeUnit.SECONDS);
            assertThat(exchange.completed).isFalse();
            assertThatThrownBy(call::execute).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void interruptingOneHttp2StreamKeepsTheConnectionUsable() throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, true, false)) {
            final Execution execution = new Execution(exchange.call, false);
            exchange.arrived.get(5, TimeUnit.SECONDS);
            final ClientRequestContext cancelled = exchange.clientContext.get(5, TimeUnit.SECONDS);
            execution.thread.interrupt();
            assertThat(execution.done.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class);
            assertThat(exchange.transportDone.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(exchange.client.get("/healthy").aggregate().get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(HttpStatus.OK);
            final ClientRequestContext healthy = exchange.healthyContext.get(5, TimeUnit.SECONDS);
            assertThat(healthy.log().whenComplete().get(5, TimeUnit.SECONDS).channel())
                    .isSameAs(cancelled.log().whenComplete().get(5, TimeUnit.SECONDS).channel());
        }
    }

    @Test
    void explicitCancelAbortsSynchronousCallBeforeServerResponse() throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, true, false)) {
            final Execution execution = new Execution(exchange.call, false);
            final ServiceRequestContext serverCtx = exchange.arrived.get(5, TimeUnit.SECONDS);
            exchange.call.cancel();
            assertThat(exchange.call.isCanceled()).isTrue();
            assertThat(execution.done.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
            assertThat(execution.interrupted.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(exchange.transportDone.get(5, TimeUnit.SECONDS)).isNotNull();
            serverCtx.whenRequestCancelling().get(5, TimeUnit.SECONDS);
            assertThat(exchange.completed).isFalse();
        }
    }

    @Test
    void publicCancelPreservesTheEarlierSynchronousInterruptCause() throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, false, false)) {
            final Execution execution = new Execution(exchange.call, false);
            exchange.arrived.get(5, TimeUnit.SECONDS);
            final ClientRequestContext ctx = exchange.clientContext.get(5, TimeUnit.SECONDS);
            execution.thread.interrupt();
            final Throwable interrupted = execution.done.get(5, TimeUnit.SECONDS);
            assertThat(interrupted).isInstanceOf(InterruptedIOException.class);
            assertThat(exchange.transportDone.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(ctx.cancellationCause()).isSameAs(interrupted);
            exchange.call.cancel();
            assertThat(ctx.cancellationCause()).isSameAs(interrupted);
        }
    }

    @Test
    void synchronousInterruptPreservesTheEarlierPublicCancellationCause() throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, false, false)) {
            final CountDownLatch failureEntered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final SubscriberFactory subscribers = (call, callback, request) ->
                    SubscriberFactory.blocking().create(call, new Callback() {
                        @Override
                        public void onFailure(Call failedCall, IOException e) {
                            failureEntered.countDown();
                            awaitLatch(release);
                            callback.onFailure(failedCall, e);
                        }

                        @Override
                        public void onResponse(Call completedCall, Response response) throws IOException {
                            callback.onResponse(completedCall, response);
                        }
                    }, request);
            final Call call = new ArmeriaCallFactory(exchange.request.url().host(),
                                                    exchange.request.url().port(), exchange.client, subscribers,
                                                    (protocol, endpoint) -> exchange.client)
                    .newCall(exchange.request);
            final Execution execution = new Execution(call, false);
            try {
                exchange.arrived.get(5, TimeUnit.SECONDS);
                final ClientRequestContext ctx = exchange.clientContext.get(5, TimeUnit.SECONDS);
                call.cancel();
                assertThat(failureEntered.await(5, TimeUnit.SECONDS)).isTrue();
                final Throwable cancellationCause = ctx.cancellationCause();
                assertThat(cancellationCause).isInstanceOf(IOException.class)
                                             .isNotInstanceOf(InterruptedIOException.class);
                execution.thread.interrupt();
                assertThat(execution.done.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class);
                assertThat(execution.interrupted.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(ctx.cancellationCause()).isSameAs(cancellationCause);
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({ "H1C, false, false", "H2C, false, false", "H2C, true, false",
                 "H2C, false, true", "H2C, true, true" })
    void explicitCancelAbortsEnqueuedCallBeforeServerResponse(SessionProtocol protocol,
                                                            boolean retry, boolean redirect)
            throws Exception {
        try (Exchange exchange = new Exchange(protocol, retry, false, redirect, redirect)) {
            final AsyncCallback callback = new AsyncCallback();
            exchange.call.enqueue(callback);
            final ServiceRequestContext serverCtx = exchange.arrived.get(5, TimeUnit.SECONDS);
            exchange.call.cancel();
            exchange.call.cancel();
            assertThat(callback.done.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
            assertThat(exchange.transportDone.get(5, TimeUnit.SECONDS)).isNotNull();
            serverCtx.whenRequestCancelling().get(5, TimeUnit.SECONDS);
            assertThat(exchange.call.isExecuted()).isTrue();
            assertThat(exchange.call.isCanceled()).isTrue();
            assertThat(callback.calls).hasValue(1);
            assertThat(exchange.completed).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancelBeforeExecutionSkipsEncodingAndLeavesCloneIndependent(boolean asynchronous) throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, false, false)) {
            final AtomicInteger writes = new AtomicInteger();
            final RequestBody body = new RequestBody() {
                @Nullable
                @Override
                public MediaType contentType() {
                    return null;
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    writes.incrementAndGet();
                    sink.writeUtf8("body");
                }
            };
            final Call call = exchange.retrofit.callFactory().newCall(exchange.request.newBuilder()
                                                                                    .post(body).build());
            call.cancel();
            call.cancel();
            if (asynchronous) {
                final AsyncCallback callback = new AsyncCallback();
                call.enqueue(callback);
                assertThat(callback.done.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
                assertThat(callback.calls).hasValue(1);
            } else {
                assertThatThrownBy(call::execute).isInstanceOf(IOException.class);
            }
            assertThat(call.isExecuted()).isTrue();
            assertThat(call.isCanceled()).isTrue();
            assertThat(writes).hasValue(0);
            assertThat(exchange.dispatched).hasValue(0);
            final Call clone = call.clone();
            assertThat(clone.isExecuted()).isFalse();
            assertThat(clone.isCanceled()).isFalse();
            exchange.complete();
            try (Response response = clone.execute()) {
                assertThat(response.code()).isEqualTo(200);
            }
            assertThat(writes).hasValue(1);
            assertThat(exchange.dispatched).hasValue(1);
            assertThat(clone.isCanceled()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancelDoesNotWaitForAsyncEncoding(boolean failEncoding) throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, false, false)) {
            final CountDownLatch writing = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final IOException encodingFailure = new IOException("encoding failed");
            final RequestBody body = new RequestBody() {
                @Nullable
                @Override
                public MediaType contentType() {
                    return null;
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    writing.countDown();
                    awaitLatch(release);
                    if (failEncoding) {
                        throw encodingFailure;
                    }
                    sink.writeUtf8("body");
                }
            };
            final Call call = exchange.retrofit.callFactory().newCall(exchange.request.newBuilder()
                                                                                    .post(body).build());
            final AsyncCallback callback = new AsyncCallback();
            final CompletableFuture<Throwable> enqueued = enqueueInThread(call, callback);
            try {
                assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(call.isExecuted()).isTrue();
                CompletableFuture.runAsync(call::cancel).get(5, TimeUnit.SECONDS);
                call.cancel();
                assertThat(enqueued).isNotDone();
                assertThat(callback.done).isNotDone();
            } finally {
                release.countDown();
            }
            if (failEncoding) {
                assertThat(enqueued.get(5, TimeUnit.SECONDS)).isInstanceOf(IllegalArgumentException.class)
                                                            .hasCause(encodingFailure);
                assertThat(callback.calls).hasValue(0);
            } else {
                assertThat(enqueued.get(5, TimeUnit.SECONDS)).isNull();
                assertThat(callback.done.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
                assertThat(callback.calls).hasValue(1);
            }
            assertThat(call.isExecuted()).isTrue();
            assertThat(call.isCanceled()).isTrue();
            assertThat(exchange.dispatched).hasValue(0);
        }
    }

    @Test
    void cancellationDuringResponsePublicationAbortsLateResponse() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final HttpResponseWriter response = HttpResponse.streaming();
        final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                          .decorator((delegate, ctx, req) -> {
                                              entered.countDown();
                                              awaitLatch(release);
                                              return response;
                                          })
                                          .build();
        final Call call = ArmeriaRetrofit.of(client).callFactory()
                                        .newCall(new Request.Builder().url("http://127.0.0.1:1/").build());
        final AsyncCallback callback = new AsyncCallback();
        final CompletableFuture<Throwable> enqueued = enqueueInThread(call, callback);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture.runAsync(call::cancel).get(5, TimeUnit.SECONDS);
            assertThat(enqueued).isNotDone();
            release.countDown();
            assertThat(enqueued.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(callback.done.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
            assertThat(response.whenComplete().handle((unused, cause) -> cause).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(IOException.class);
            assertThat(callback.calls).hasValue(1);
        } finally {
            release.countDown();
            response.abort();
        }
    }

    @Test
    void cancelAfterStreamingHandoffUnblocksBodyReaderWithoutAnotherCallback() throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, true, true)) {
            final CompletableFuture<Response> delivered = new CompletableFuture<>();
            final AtomicInteger callbacks = new AtomicInteger();
            exchange.call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callbacks.incrementAndGet();
                    delivered.completeExceptionally(e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    callbacks.incrementAndGet();
                    delivered.complete(response);
                }
            });
            final ServiceRequestContext serverCtx = exchange.arrived.get(5, TimeUnit.SECONDS);
            exchange.response.write(ResponseHeaders.of(HttpStatus.OK));
            exchange.response.write(HttpData.ofUtf8("first-"));
            try (Response response = delivered.get(5, TimeUnit.SECONDS)) {
                final BufferedSource source = response.body().source();
                assertThat(source.readUtf8(6)).isEqualTo("first-");
                final CompletableFuture<Throwable> read = new CompletableFuture<>();
                final Thread reader = new Thread(() -> {
                    try {
                        source.readByte();
                        read.complete(null);
                    } catch (Throwable cause) {
                        read.complete(cause);
                    }
                });
                reader.setDaemon(true);
                reader.start();
                await().atMost(5, TimeUnit.SECONDS).untilAsserted(
                        () -> assertThat(reader.getState()).isEqualTo(Thread.State.WAITING));
                exchange.call.cancel();
                exchange.call.cancel();
                assertThat(read.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
                assertThat(exchange.transportDone.get(5, TimeUnit.SECONDS)).isNotNull();
                serverCtx.whenRequestCancelling().get(5, TimeUnit.SECONDS);
                assertThat(callbacks).hasValue(1);
                assertThat(exchange.completed).isFalse();
            }
        }
    }

    @Test
    void enqueueDoesNotUseCallingThreadsInterruptStatus() throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, true, false)) {
            final CompletableFuture<Throwable> done = new CompletableFuture<>();
            Thread.currentThread().interrupt();
            try {
                exchange.call.enqueue(new Callback() {
                    @Override
                    public void onResponse(Call call, Response response) {
                        response.close();
                        done.complete(null);
                    }

                    @Override
                    public void onFailure(Call call, IOException e) {
                        done.complete(e);
                    }
                });
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            exchange.arrived.get(5, TimeUnit.SECONDS);
            assertThat(exchange.call.isCanceled()).isFalse();
            exchange.complete();
            assertThat(done.get(5, TimeUnit.SECONDS)).isNull();
        }
    }

    @Test
    void alreadyInterruptedCallClaimsExecutionWithoutDispatching() throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, false, false)) {
            final AtomicInteger writes = new AtomicInteger();
            final RequestBody body = new RequestBody() {
                @Nullable
                @Override
                public MediaType contentType() {
                    return null;
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    writes.incrementAndGet();
                    sink.writeUtf8("body");
                }
            };
            final Call call = exchange.retrofit.callFactory().newCall(exchange.request.newBuilder()
                                                                                    .post(body).build());
            final Execution execution = new Execution(call, true);
            assertThat(execution.done.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class)
                                                             .hasCauseInstanceOf(InterruptedException.class);
            assertThat(execution.interrupted.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(call.isExecuted()).isTrue();
            assertThat(call.isCanceled()).isTrue();
            assertThat(writes).hasValue(0);
            assertThat(exchange.arrived).isNotDone();
            assertThat(exchange.dispatched).hasValue(0);
            assertThatThrownBy(call::execute).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> call.enqueue(mock(Callback.class)))
                    .isInstanceOf(IllegalStateException.class);
            final Call clone = call.clone();
            assertThat(clone.isExecuted()).isFalse();
            assertThat(clone.isCanceled()).isFalse();
            exchange.complete();
            try (Response response = clone.execute()) {
                assertThat(response.code()).isEqualTo(200);
            }
            assertThat(writes).hasValue(1);
            assertThat(exchange.dispatched).hasValue(1);
            assertThat(clone.isExecuted()).isTrue();
            assertThat(clone.isCanceled()).isFalse();
        }
    }

    @Test
    void interruptDuringEncodingIsCheckedBeforeDispatch() throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, false, false)) {
            final CountDownLatch writing = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final RequestBody body = new RequestBody() {
                @Nullable
                @Override
                public MediaType contentType() {
                    return null;
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    writing.countDown();
                    boolean interrupted = false;
                    try {
                        for (;;) {
                            try {
                                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                                break;
                            } catch (InterruptedException e) {
                                interrupted = true;
                            }
                        }
                        sink.writeUtf8("body");
                    } finally {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            };
            final Call call = exchange.retrofit.callFactory().newCall(exchange.request.newBuilder()
                                                                                    .post(body).build());
            final Execution execution = new Execution(call, false);
            try {
                assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(call.isExecuted()).isTrue();
                final Execution reexecution = new Execution(call, false);
                assertThat(reexecution.done.get(5, TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> call.enqueue(mock(Callback.class)))
                        .isInstanceOf(IllegalStateException.class);
                execution.thread.interrupt();
            } finally {
                release.countDown();
            }
            assertThat(execution.done.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class);
            assertThat(execution.interrupted.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(call.isCanceled()).isTrue();
            assertThat(exchange.dispatched).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void encodingFailureClaimsExecution(boolean asynchronous) {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, false, false)) {
            final AtomicInteger writes = new AtomicInteger();
            final IOException cause = new IOException("encoding failed");
            final RequestBody body = new RequestBody() {
                @Nullable
                @Override
                public MediaType contentType() {
                    return null;
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    writes.incrementAndGet();
                    throw cause;
                }
            };
            final Call call = exchange.retrofit.callFactory().newCall(exchange.request.newBuilder()
                                                                                    .post(body).build());
            final ThrowingCallable execute =
                    asynchronous ? () -> call.enqueue(mock(Callback.class)) : call::execute;
            assertThatThrownBy(execute).isInstanceOf(IllegalArgumentException.class).hasCause(cause);
            assertThat(call.isExecuted()).isTrue();
            assertThat(call.isCanceled()).isFalse();
            assertThatThrownBy(call::execute).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> call.enqueue(mock(Callback.class)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(writes).hasValue(1);
            assertThat(exchange.dispatched).hasValue(0);
        }
    }

    @Test
    void interruptionAfterResponsePublicationClosesUnclaimedSuccess() {
        final HttpResponseWriter httpResponse = HttpResponse.streaming();
        final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                          .decorator((delegate, ctx, req) -> httpResponse)
                                          .build();
        final Request request = new Request.Builder().url("http://127.0.0.1:1/").build();
        final ResponseBody body = mock(ResponseBody.class);
        final Response response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                                                        .code(200).message("OK").body(body).build();
        final SubscriberFactory subscriberFactory = (call, callback, req) -> {
            try {
                callback.onResponse(call, response);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            Thread.currentThread().interrupt();
            return SubscriberFactory.blocking().create(call, callback, req);
        };
        final Call call = new ArmeriaCallFactory("127.0.0.1", 1, client, subscriberFactory,
                                                (protocol, endpoint) -> client).newCall(request);
        try {
            assertThatThrownBy(call::execute).isInstanceOf(InterruptedIOException.class)
                                            .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(call.isCanceled()).isTrue();
            verify(body).close();
        } finally {
            Thread.interrupted();
            httpResponse.abort();
        }
    }

    @Test
    void cancelBeforeExecutePreservesCancellationState() throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, false, false)) {
            exchange.call.cancel();
            final Execution execution = new Execution(exchange.call, false);
            assertThat(execution.done.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
            assertThat(execution.interrupted.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(exchange.call.isExecuted()).isTrue();
            assertThat(exchange.call.isCanceled()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void followRedirectsPreservesInterruptionContract(boolean redirect) throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, false, false, true, redirect)) {
            final Execution execution = new Execution(exchange.call, false);
            final ServiceRequestContext serverCtx = exchange.arrived.get(5, TimeUnit.SECONDS);
            if (redirect) {
                exchange.redirected.get(5, TimeUnit.SECONDS);
            }
            execution.thread.interrupt();
            assertThat(execution.done.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class);
            assertThat(exchange.call.isCanceled()).isTrue();
            assertThat(exchange.transportDone.get(5, TimeUnit.SECONDS)).isNotNull();
            serverCtx.whenRequestCancelling().get(5, TimeUnit.SECONDS);
            assertThat(exchange.completed).isFalse();
            assertThat(execution.interrupted.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void streamingGuaranteeEndsWhenRawResponseIsHandedOff() throws Exception {
        try (Exchange exchange = new Exchange(SessionProtocol.H2C, true, true)) {
            exchange.response.write(ResponseHeaders.of(HttpStatus.OK));
            exchange.response.write(HttpData.ofUtf8("first-"));
            try (Response response = exchange.call.execute()) {
                assertThat(response.code()).isEqualTo(200);
                assertThat(exchange.transportDone).isNotDone();
                Thread.currentThread().interrupt();
                try {
                    assertThat(exchange.call.isCanceled()).isFalse();
                    assertThat(exchange.transportDone).isNotDone();
                } finally {
                    Thread.interrupted();
                }
            }
        }
    }

    @Test
    void ordinaryFailureKeepsIOExceptionMapping() {
        final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                          .decorator((delegate, ctx, req) ->
                                                             HttpResponse.ofFailure(new AnticipatedException()))
                                          .build();
        final Call call = ArmeriaRetrofit.builder(client).build()
                                        .callFactory().newCall(new Request.Builder()
                                                                      .url("http://127.0.0.1:1/").build());
        assertThatThrownBy(call::execute).isInstanceOf(IOException.class)
                                        .hasRootCauseInstanceOf(AnticipatedException.class);
        assertThat(call.isCanceled()).isFalse();
    }

    private static CompletableFuture<Throwable> enqueueInThread(Call call, Callback callback) {
        final CompletableFuture<Throwable> result = new CompletableFuture<>();
        final Thread thread = new Thread(() -> {
            try {
                call.enqueue(callback);
                result.complete(null);
            } catch (Throwable cause) {
                result.complete(cause);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class AsyncCallback implements Callback {
        final CompletableFuture<Throwable> done = new CompletableFuture<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void onFailure(Call call, IOException e) {
            calls.incrementAndGet();
            done.complete(e);
        }

        @Override
        public void onResponse(Call call, Response response) {
            calls.incrementAndGet();
            response.close();
            done.complete(null);
        }
    }

    private static final class Execution {

        final CompletableFuture<Throwable> done = new CompletableFuture<>();
        final CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        final Thread thread;

        Execution(Call call, boolean interruptBeforeExecute) {
            thread = new Thread(() -> {
                if (interruptBeforeExecute) {
                    Thread.currentThread().interrupt();
                }
                try (Response ignored = call.execute()) {
                    done.complete(null);
                } catch (Throwable t) {
                    done.complete(t);
                } finally {
                    interrupted.complete(Thread.currentThread().isInterrupted());
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static final class Exchange implements AutoCloseable {

        final String path = "/held/" + nextId.incrementAndGet();
        final HttpResponseWriter response = HttpResponse.streaming();
        final CompletableFuture<ServiceRequestContext> arrived = new CompletableFuture<>();
        final CompletableFuture<Void> redirected = new CompletableFuture<>();
        final CompletableFuture<ClientRequestContext> clientContext = new CompletableFuture<>();
        final CompletableFuture<ClientRequestContext> healthyContext = new CompletableFuture<>();
        final CompletableFuture<Throwable> transportDone = new CompletableFuture<>();
        final AtomicInteger dispatched = new AtomicInteger();
        final ClientFactory factory = ClientFactory.builder().build();
        final WebClient client;
        final Retrofit retrofit;
        final Request request;
        final Call call;
        boolean completed;

        Exchange(SessionProtocol protocol, boolean retry, boolean streaming) {
            this(protocol, retry, streaming, false, false);
        }

        Exchange(SessionProtocol protocol, boolean retry, boolean streaming,
                 boolean followRedirects, boolean redirect) {
            exchanges.put(path, this);
            final WebClientBuilder builder = WebClient.builder(protocol, server.httpEndpoint())
                                                     .factory(factory)
                                                     .responseTimeoutMillis(0)
                                                     .decorator((delegate, ctx, req) -> {
                                                         final HttpResponse result = delegate.execute(ctx, req);
                                                         if (req.path().equals(path)) {
                                                             dispatched.incrementAndGet();
                                                             clientContext.complete(ctx);
                                                             result.whenComplete().handle((unused, cause) -> {
                                                                 transportDone.complete(cause);
                                                                 return null;
                                                             });
                                                         } else {
                                                             healthyContext.complete(ctx);
                                                         }
                                                         return result;
                                                     });
            if (retry) {
                builder.decorator(RetryingClient.newDecorator(RetryRule.onException()));
            }
            if (followRedirects) {
                builder.followRedirects();
            }
            client = builder.build();
            retrofit = ArmeriaRetrofit.builder(client).streaming(streaming).build();
            request = new Request.Builder().url(server.httpUri().resolve(redirect ? "/redirect" + path : path)
                                                                .toString()).build();
            call = retrofit.callFactory().newCall(request);
        }

        void complete() {
            completed = true;
            response.write(ResponseHeaders.of(HttpStatus.OK));
            response.write(HttpData.ofUtf8("body"));
            response.close();
        }

        @Override
        public void close() {
            response.abort();
            factory.close();
            exchanges.remove(path);
        }
    }
}

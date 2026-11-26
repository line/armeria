/*
 * Copyright 2026 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.limit.ConcurrencyLimit;
import com.linecorp.armeria.client.limit.ConcurrencyLimitingClient;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryDecision;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Invocation;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Url;

class ArmeriaCallCancellationIntegrationTest {

    interface Service {
        @GET
        Call<ResponseBody> get(@Url String path);

        @GET
        CompletableFuture<ResponseBody> future(@Url String path);
    }

    private static final AtomicInteger nextId = new AtomicInteger();
    private static final ConcurrentMap<String, Exchange> exchanges = new ConcurrentHashMap<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(0);
            sb.serviceUnder("/", (ctx, req) -> {
                final Exchange exchange = exchanges.get(ctx.path());
                if (exchange == null) {
                    return HttpResponse.of(HttpStatus.NOT_FOUND);
                }
                if (exchange.attempts.incrementAndGet() == 1 && exchange.failFirst) {
                    return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                }
                exchange.started.complete(ctx);
                return exchange.response;
            });
        }
    };

    private final ClientFactory clientFactory = ClientFactory.builder().build();

    @AfterEach
    void tearDown() {
        exchanges.values().forEach(exchange -> exchange.response.abort());
        exchanges.clear();
        clientFactory.close();
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void interruptStopsExecuteAndServerRequest(SessionProtocol protocol) throws Exception {
        final Exchange exchange = new Exchange();
        final Call<ResponseBody> call = builder(protocol).build().create(Service.class).get(exchange.path);
        try (RunningCall running = new RunningCall(call)) {
            final ServiceRequestContext serverCtx = exchange.started.get(5, TimeUnit.SECONDS);
            running.thread.interrupt();
            assertThat(running.result.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
            assertThat(running.interrupted.get()).isTrue();
            assertThat(call.isCanceled()).isTrue();
            assertServerCancelled(serverCtx);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void interruptThrowsInterruptedIOException(SessionProtocol protocol) throws Exception {
        final Exchange exchange = new Exchange();
        final Call<ResponseBody> call = builder(protocol).build().create(Service.class).get(exchange.path);
        try (RunningCall running = new RunningCall(call)) {
            exchange.started.get(5, TimeUnit.SECONDS);
            running.thread.interrupt();
            assertThat(running.result.get(3, TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void interruptVirtualThreadStopsServerRequest(SessionProtocol protocol) throws Exception {
        Assumptions.assumeTrue(SystemInfo.javaVersion() >= 21);
        final Exchange exchange = new Exchange();
        final Call<ResponseBody> call = builder(protocol).build().create(Service.class).get(exchange.path);
        try (RunningCall running = new RunningCall(call, true)) {
            final ServiceRequestContext serverCtx = exchange.started.get(5, TimeUnit.SECONDS);
            assertThat(Thread.class.getMethod("isVirtual").invoke(running.thread)).isEqualTo(true);
            running.thread.interrupt();
            assertThat(running.result.get(3, TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class);
            assertThat(running.interrupted.get()).isTrue();
            assertServerCancelled(serverCtx);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void interruptClosesResponseDeliveredAfterWaiterExits(SessionProtocol protocol) throws Exception {
        final Exchange exchange = new Exchange();
        final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        final CompletableFuture<CompletableCallback> callbackCreated = new CompletableFuture<>();
        final CompletableFuture<ClientRequestContext> contextCreated = new CompletableFuture<>();
        final WebClient client = WebClient.builder(server.uri(protocol))
                                         .factory(clientFactory).responseTimeoutMillis(0)
                                         .contextCustomizer(contextCreated::complete).build();
        final ArmeriaCallFactory factory = new ArmeriaCallFactory(
                client.uri().getHost(), client.uri().getPort(), client,
                (call, callback, request) -> {
                    callbackCreated.complete((CompletableCallback) callback);
                    return new StreamingCallSubscriber(call, callback, request, callbacks::add);
                }, (unused, endpoint) -> {
                    throw new AssertionError("Unexpected non-base client");
                });
        final okhttp3.Call call = factory.newCall(new okhttp3.Request.Builder()
                .url(server.httpUri().resolve(exchange.path).toString()).build());
        final CompletableFuture<Throwable> result = new CompletableFuture<>();
        final Thread thread = new Thread(() -> {
            try (okhttp3.Response ignored = call.execute()) {
                result.complete(new AssertionError("Call returned before callback delivery"));
            } catch (Throwable cause) {
                result.complete(cause);
            }
        }, "retrofit-interrupted-callback-test");
        thread.start();
        try {
            exchange.started.get(5, TimeUnit.SECONDS);
            exchange.response.write(ResponseHeaders.of(HttpStatus.OK));
            exchange.response.write(HttpData.ofUtf8("body"));
            exchange.response.close();
            final Runnable deliver = callbacks.poll(5, TimeUnit.SECONDS);
            assertThat(deliver).isNotNull();
            contextCreated.get(5, TimeUnit.SECONDS).log().whenComplete().get(5, TimeUnit.SECONDS);
            thread.interrupt();
            assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class);
            deliver.run();
            try (okhttp3.Response response = callbackCreated.get().get(3, TimeUnit.SECONDS)) {
                assertThatThrownBy(() -> response.body().source().readByte())
                        .isInstanceOf(IllegalStateException.class).hasMessage("closed");
            }
        } finally {
            call.cancel();
            thread.interrupt();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancelStopsEnqueueAndServerRequest(SessionProtocol protocol) throws Exception {
        final Exchange exchange = new Exchange();
        final Call<ResponseBody> call = builder(protocol).build().create(Service.class).get(exchange.path);
        final CompletableFuture<Throwable> result = enqueue(call);
        final ServiceRequestContext serverCtx = exchange.started.get(5, TimeUnit.SECONDS);
        call.cancel();
        assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
        assertServerCancelled(serverCtx);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancelDuringResponseCreationStopsServerRequest(SessionProtocol protocol) throws Exception {
        final Exchange exchange = new Exchange();
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final Call<ResponseBody> call = builder(protocol)
                .decorator((delegate, ctx, req) -> {
                    final HttpResponse response = delegate.execute(ctx, req);
                    release.get(5, TimeUnit.SECONDS);
                    return response;
                }).build().create(Service.class).get(exchange.path);
        try (RunningCall running = new RunningCall(call)) {
            try {
                final ServiceRequestContext serverCtx = exchange.started.get(5, TimeUnit.SECONDS);
                call.cancel();
                release.complete(null);
                assertThat(running.result.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
                assertServerCancelled(serverCtx);
            } finally {
                release.complete(null);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancelFutureStopsServerRequest(SessionProtocol protocol) throws Exception {
        final Exchange exchange = new Exchange();
        final CompletableFuture<ResponseBody> response =
                builder(protocol).build().create(Service.class).future(exchange.path);
        final ServiceRequestContext serverCtx = exchange.started.get(5, TimeUnit.SECONDS);
        assertThat(response.cancel(true)).isTrue();
        assertServerCancelled(serverCtx);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancelStopsInFlightRetryAttempt(SessionProtocol protocol) throws Exception {
        final Exchange exchange = new Exchange();
        final Call<ResponseBody> call = builder(protocol)
                .decorator(RetryingClient.newDecorator(RetryRule.onServerErrorStatus(Backoff.withoutDelay())))
                .build().create(Service.class).get(exchange.path);
        final CompletableFuture<Throwable> result = enqueue(call);
        final ServiceRequestContext serverCtx = exchange.started.get(5, TimeUnit.SECONDS);
        call.cancel();
        assertServerCancelled(serverCtx);
        assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancelStopsSecondRetryAttempt(SessionProtocol protocol) throws Exception {
        final Exchange exchange = new Exchange(true);
        final AtomicReference<Invocation> invocation = new AtomicReference<>();
        final Call<ResponseBody> call = builder(protocol)
                .contextCustomizer(ctx -> invocation.set(InvocationUtil.getInvocation(ctx.log())))
                .decorator((delegate, ctx, req) -> {
                    final RequestLogAccess log = ctx.log();
                    assertThat(InvocationUtil.getInvocation(log)).isNotNull().isSameAs(invocation.get());
                    assertThat(log.partial().serviceName()).isEqualTo(Service.class.getName());
                    assertThat(log.partial().name()).isEqualTo("get");
                    return delegate.execute(ctx, req);
                })
                .decorator(RetryingClient.newDecorator(RetryRule.onServerErrorStatus(Backoff.withoutDelay())))
                .build().create(Service.class).get(exchange.path);
        final CompletableFuture<Throwable> result = enqueue(call);
        final ServiceRequestContext serverCtx = exchange.started.get(5, TimeUnit.SECONDS);
        assertThat(exchange.attempts).hasValue(2);
        call.cancel();
        assertServerCancelled(serverCtx);
        assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancellingSubscriptionStopsRetryAttempt(SessionProtocol protocol) throws Exception {
        final Exchange exchange = new Exchange();
        final WebClient client = WebClient.builder(server.uri(protocol))
                .factory(clientFactory).responseTimeoutMillis(0)
                .decorator(RetryingClient.newDecorator(RetryRule.onServerErrorStatus(Backoff.withoutDelay())))
                .build();
        final CompletableFuture<Subscription> subscription = subscribeWithoutDemand(client.get(exchange.path));
        final ServiceRequestContext serverCtx = exchange.started.get(5, TimeUnit.SECONDS);
        subscription.get(5, TimeUnit.SECONDS).cancel();
        assertServerCancelled(serverCtx);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancellingSubscriptionStopsRedirectAttempt(SessionProtocol protocol) throws Exception {
        final Exchange redirect = new Exchange();
        final Exchange target = new Exchange();
        redirect.response.write(ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT,
                                                  HttpHeaderNames.LOCATION, target.path));
        redirect.response.close();
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .factory(clientFactory).responseTimeoutMillis(0)
                                          .followRedirects().build();
        final CompletableFuture<Subscription> subscription = subscribeWithoutDemand(client.get(redirect.path));
        final ServiceRequestContext serverCtx = target.started.get(5, TimeUnit.SECONDS);
        subscription.get(5, TimeUnit.SECONDS).cancel();
        assertServerCancelled(serverCtx);
    }

    @Test
    void cancelWhileRetryDecisionIsPendingDiscardsBufferedBody() throws Exception {
        final Exchange exchange = new Exchange();
        final CompletableFuture<ClientRequestContext> ruleEntered = new CompletableFuture<>();
        final CompletableFuture<RetryDecision> decision = new CompletableFuture<>();
        final RetryRule rule = (ctx, cause) -> {
            ruleEntered.complete(ctx);
            return decision;
        };
        try (ClientFactory factory = ClientFactory.builder().workerGroup(1).build()) {
            final Call<ResponseBody> call = builder(SessionProtocol.H2C).factory(factory)
                    .decorator(RetryingClient.newDecorator(rule))
                    .build().create(Service.class).get(exchange.path);
            final CompletableFuture<Throwable> result = enqueue(call);
            final ServiceRequestContext serverCtx = exchange.started.get(5, TimeUnit.SECONDS);
            try {
                exchange.response.write(ResponseHeaders.of(HttpStatus.OK));
                exchange.response.write(HttpData.ofUtf8("body"));
                final ClientRequestContext attemptCtx = ruleEntered.get(5, TimeUnit.SECONDS);
                exchange.response.whenConsumed().get(5, TimeUnit.SECONDS);
                final Exchange probe = new Exchange();
                probe.response.write(ResponseHeaders.of(HttpStatus.OK));
                probe.response.close();
                final WebClient probeClient = WebClient.builder(server.uri(SessionProtocol.H2C))
                                                      .factory(factory).build();
                try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                    probeClient.get(probe.path).aggregate().get(5, TimeUnit.SECONDS);
                    assertThat(captor.get().log().whenComplete().join().channel())
                            .isSameAs(attemptCtx.log().partial().channel());
                }
                call.cancel();
                assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
                assertServerCancelled(serverCtx);
            } finally {
                decision.complete(RetryDecision.noRetry());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancelWhileWaitingForRetryBackoffCompletesCall(SessionProtocol protocol) throws Exception {
        final Exchange exchange = new Exchange();
        exchange.response.write(ResponseHeaders.of(HttpStatus.SERVICE_UNAVAILABLE));
        exchange.response.close();
        final CompletableFuture<Void> backoffReached = new CompletableFuture<>();
        final Backoff backoff = attempt -> {
            backoffReached.complete(null);
            return 60_000;
        };
        final Call<ResponseBody> call = builder(protocol)
                .decorator(RetryingClient.newDecorator(RetryRule.onServerErrorStatus(backoff)))
                .build().create(Service.class).get(exchange.path);
        final CompletableFuture<Throwable> result = enqueue(call);
        backoffReached.get(5, TimeUnit.SECONDS);
        call.cancel();
        assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancelStopsRedirectedRequest(SessionProtocol protocol) throws Exception {
        final Exchange redirect = new Exchange();
        final Exchange target = new Exchange();
        redirect.response.write(ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT,
                                                  HttpHeaderNames.LOCATION, target.path));
        redirect.response.close();
        final Call<ResponseBody> call = builder(protocol).followRedirects()
                .build().create(Service.class).get(redirect.path);
        final CompletableFuture<Throwable> result = enqueue(call);
        final ServiceRequestContext targetCtx = target.started.get(5, TimeUnit.SECONDS);
        call.cancel();
        assertServerCancelled(targetCtx);
        assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancelWhileWaitingForConcurrencyPermitCompletesCall(SessionProtocol protocol) throws Exception {
        final Exchange first = new Exchange();
        final Exchange queued = new Exchange();
        final ConcurrencyLimit limit = ConcurrencyLimit.builder(1)
                                                       .maxPendingAcquisitions(1)
                                                       .timeoutMillis(0)
                                                       .build();
        final Service service = builder(protocol)
                .decorator(ConcurrencyLimitingClient.newDecorator(limit))
                .build().create(Service.class);
        final Call<ResponseBody> activeCall = service.get(first.path);
        enqueue(activeCall);
        first.started.get(5, TimeUnit.SECONDS);
        final Call<ResponseBody> queuedCall = service.get(queued.path);
        final CompletableFuture<Throwable> result;
        final ClientRequestContext queuedCtx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            result = enqueue(queuedCall);
            queuedCtx = captor.get();
        }
        queuedCtx.eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
        try {
            queuedCall.cancel();
            assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
            assertThat(queued.started).isNotCompleted();
        } finally {
            activeCall.cancel();
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void cancelDoesNotCancelIndependentRequestStartedByDecorator(SessionProtocol protocol) throws Exception {
        final Exchange original = new Exchange();
        final Exchange independent = new Exchange();
        final WebClient otherClient = WebClient.builder(server.uri(protocol))
                                               .factory(clientFactory)
                                               .responseTimeoutMillis(0)
                                               .build();
        final CompletableFuture<AggregatedHttpResponse> independentResult = new CompletableFuture<>();
        final Call<ResponseBody> call = builder(protocol)
                .decorator((delegate, ctx, req) -> {
                    otherClient.get(independent.path).aggregate().whenComplete((response, cause) -> {
                        if (cause != null) {
                            independentResult.completeExceptionally(cause);
                        } else {
                            independentResult.complete(response);
                        }
                    });
                    return delegate.execute(ctx, req);
                })
                .build().create(Service.class).get(original.path);
        enqueue(call);
        final ServiceRequestContext originalCtx = original.started.get(5, TimeUnit.SECONDS);
        independent.started.get(5, TimeUnit.SECONDS);
        call.cancel();
        independent.response.write(ResponseHeaders.of(HttpStatus.OK));
        independent.response.close();
        assertThat(independentResult.get(3, TimeUnit.SECONDS).status()).isEqualTo(HttpStatus.OK);
        assertServerCancelled(originalCtx);
    }

    private ArmeriaRetrofitBuilder builder(SessionProtocol protocol) {
        return ArmeriaRetrofit.builder(server.uri(protocol)).factory(clientFactory).responseTimeoutMillis(0);
    }

    private static CompletableFuture<Throwable> enqueue(Call<ResponseBody> call) {
        final CompletableFuture<Throwable> result = new CompletableFuture<>();
        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> unused, Response<ResponseBody> response) {
                if (response.body() != null) {
                    response.body().close();
                }
                result.complete(new AssertionError("Received a response instead of cancellation"));
            }

            @Override
            public void onFailure(Call<ResponseBody> unused, Throwable cause) {
                result.complete(cause);
            }
        });
        return result;
    }

    private static CompletableFuture<Subscription> subscribeWithoutDemand(HttpResponse response) {
        final CompletableFuture<Subscription> subscribed = new CompletableFuture<>();
        response.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscribed.complete(subscription);
            }

            @Override
            public void onNext(HttpObject item) {}

            @Override
            public void onError(Throwable cause) {}

            @Override
            public void onComplete() {}
        });
        return subscribed;
    }

    private static void assertServerCancelled(ServiceRequestContext ctx) throws Exception {
        assertThat(ctx.log().whenComplete().get(3, TimeUnit.SECONDS).responseCause()).isNotNull();
    }

    private static final class Exchange {
        final String path = "/cancel-" + nextId.incrementAndGet();
        final HttpResponseWriter response = HttpResponse.streaming();
        final CompletableFuture<ServiceRequestContext> started = new CompletableFuture<>();
        final AtomicInteger attempts = new AtomicInteger();
        final boolean failFirst;

        Exchange() {
            this(false);
        }

        Exchange(boolean failFirst) {
            this.failFirst = failFirst;
            exchanges.put(path, this);
        }
    }

    private static final class RunningCall implements AutoCloseable {
        final CompletableFuture<Throwable> result = new CompletableFuture<>();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final Call<ResponseBody> call;
        final Thread thread;

        RunningCall(Call<ResponseBody> call) throws ReflectiveOperationException {
            this(call, false);
        }

        RunningCall(Call<ResponseBody> call, boolean virtual) throws ReflectiveOperationException {
            this.call = call;
            final Runnable task = () -> {
                Throwable failure;
                try {
                    final Response<ResponseBody> response = call.execute();
                    if (response.body() != null) {
                        response.body().close();
                    }
                    failure = new AssertionError("Received a response instead of cancellation");
                } catch (Throwable cause) {
                    failure = cause;
                }
                interrupted.set(Thread.currentThread().isInterrupted());
                result.complete(failure);
            };
            if (virtual) {
                thread = (Thread) Thread.class.getMethod("startVirtualThread", Runnable.class)
                                             .invoke(null, task);
            } else {
                thread = new Thread(task, "retrofit-cancellation-test");
                thread.start();
            }
        }

        @Override
        public void close() throws InterruptedException {
            call.cancel();
            thread.interrupt();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        }
    }
}

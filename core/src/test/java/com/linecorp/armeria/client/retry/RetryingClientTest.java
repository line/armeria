/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.client.retry.AbstractRetryingClient.ARMERIA_RETRY_COUNT;
import static com.linecorp.armeria.common.util.Exceptions.peel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.EventLoop;

class RetryingClientTest {

    private static final RetryRule retryAlways =
            (ctx, cause) -> CompletableFuture.completedFuture(RetryDecision.retry(Backoff.fixed(500)));

    private static ClientFactory clientFactory;

    @BeforeAll
    static void beforeAll() {
        // use different eventLoop from server's so that clients don't hang when the eventLoop in server hangs
        clientFactory = ClientFactory.builder().workerGroup(EventLoopGroups.newEventLoopGroup(2), true).build();
    }

    @AfterAll
    static void afterAll() {
        clientFactory.closeAsync();
    }

    private final AtomicInteger responseAbortServiceCallCounter = new AtomicInteger();

    private final AtomicInteger requestAbortServiceCallCounter = new AtomicInteger();

    private final AtomicInteger subscriberCancelServiceCallCounter = new AtomicInteger();

    private AtomicInteger reqCount;

    @RegisterExtension
    final ServerExtension server = new ServerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/retry-content", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    final int retryCount = reqCount.getAndIncrement();
                    if (retryCount != 0) {
                        assertThat(retryCount).isEqualTo(req.headers().getInt(ARMERIA_RETRY_COUNT));
                    }
                    if (retryCount < 2) {
                        return HttpResponse.of("Need to retry");
                    } else {
                        return HttpResponse.of("Succeeded after retry");
                    }
                }
            });

            sb.service("/500-always", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(HttpStatus.valueOf(500));
                }
            });

            sb.service("/501-always", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(HttpStatus.valueOf(501));
                }
            });

            sb.service("/502-always", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(HttpStatus.valueOf(502));
                }
            });

            sb.service("/500-then-success", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                    } else {
                        return HttpResponse.of("Succeeded after retry");
                    }
                }
            });

            sb.service("/503-then-success", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                    } else {
                        return HttpResponse.of("Succeeded after retry");
                    }
                }
            });

            sb.service("/trailers-then-success", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    final boolean success = reqCount.getAndIncrement() >= 1;
                    return HttpResponse.of(
                            ResponseHeaders.of(200),
                            HttpData.ofUtf8(success ? "Succeeded after retry" : "See the trailers"),
                            HttpHeaders.of("grpc-status", success ? 0 : 3));
                }
            });

            sb.service("/retry-after-1-second", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        return HttpResponse.of(
                                ResponseHeaders.of(HttpStatus.SERVICE_UNAVAILABLE,
                                                   HttpHeaderNames.RETRY_AFTER, "1" /* second */));
                    } else {
                        return HttpResponse.of("Succeeded after retry");
                    }
                }
            });

            sb.service("/retry-after-with-http-date", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        return HttpResponse.of(
                                ResponseHeaders.builder(HttpStatus.SERVICE_UNAVAILABLE)
                                               .setTimeMillis(HttpHeaderNames.RETRY_AFTER,
                                                              Duration.ofSeconds(3).toMillis() +
                                                              System.currentTimeMillis())
                                               .build());
                    } else {
                        return HttpResponse.of(
                                HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }
            });

            sb.service("/retry-after-one-year", new AbstractHttpService() {

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(
                            ResponseHeaders.builder(HttpStatus.SERVICE_UNAVAILABLE)
                                           .setTimeMillis(HttpHeaderNames.RETRY_AFTER,
                                                          Duration.ofDays(365).toMillis() +
                                                          System.currentTimeMillis())
                                           .build());
                }
            });

            sb.service("/service-unavailable", new AbstractHttpService() {

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                }
            });

            sb.service("/1sleep-then-success", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        return HttpResponse.delayed(
                                HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE), Duration.ofSeconds(1));
                    } else {
                        return HttpResponse.of(
                                HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }
            });

            sb.service("/post-ping-pong", new AbstractHttpService() {
                final AtomicInteger reqPostCount = new AtomicInteger();

                @Override
                protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.from(req.aggregate().handle((aggregatedRequest, thrown) -> {
                        if (reqPostCount.getAndIncrement() < 1) {
                            return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                        } else {
                            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                                   aggregatedRequest.contentUtf8());
                        }
                    }));
                }
            });

            sb.service("/response-abort", new AbstractHttpService() {

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    responseAbortServiceCallCounter.incrementAndGet();
                    return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                }
            });

            sb.service("/request-abort", new AbstractHttpService() {

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    requestAbortServiceCallCounter.incrementAndGet();
                    return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                }
            });

            sb.service("/subscriber-cancel", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    if (subscriberCancelServiceCallCounter.getAndIncrement() < 2) {
                        return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                    } else {
                        return HttpResponse.of("Succeeded after retry");
                    }
                }
            });
        }
    };

    @BeforeEach
    void setUp() {
        reqCount = new AtomicInteger();
    }

    @Test
    void retryWhenContentMatched() {
        final Function<? super HttpClient, RetryingClient> retryingDecorator =
                RetryingClient.builder(new RetryIfContentMatch("Need to retry"), 1024)
                              .newDecorator();
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(retryingDecorator)
                                          .build();

        final AggregatedHttpResponse res = client.get("/retry-content").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    void retryWhenStatusMatched() {
        final WebClient client = client(RetryRule.builder().onServerErrorStatus().onException().thenBackoff());
        final AggregatedHttpResponse res = client.get("/503-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    void retryWhenStatusMatchedWithContent() {
        final WebClient client = client(RetryRuleWithContent.<HttpResponse>builder()
                                                .onServerErrorStatus()
                                                .onException()
                                                .thenBackoff(), 10000, 0, 100);
        final AggregatedHttpResponse res = client.get("/503-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    void retryWhenTrailerMatched() {
        final WebClient client =
                client(RetryRule.builder()
                                .onResponseTrailers((unused, trailers) -> {
                                    return trailers.getInt("grpc-status", -1) != 0;
                                })
                                .thenBackoff());
        final AggregatedHttpResponse res = client.get("/trailers-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    void disableResponseTimeout() {
        final WebClient client = client(RetryRule.failsafe(), 0, 0, 100);
        final AggregatedHttpResponse res = client.get("/503-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
        // response timeout did not happen.
    }

    @Test
    void respectRetryAfter() {
        final WebClient client = client(RetryRule.failsafe());
        final Stopwatch sw = Stopwatch.createStarted();

        final AggregatedHttpResponse res = client.get("/retry-after-1-second").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (TimeUnit.SECONDS.toMillis(1) * 0.9));
    }

    @Test
    void respectRetryAfterWithHttpDate() {
        final WebClient client = client(RetryRule.failsafe());

        final Stopwatch sw = Stopwatch.createStarted();
        final AggregatedHttpResponse res = client.get("/retry-after-with-http-date").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        // Since ZonedDateTime doesn't express exact time,
        // just check out whether it is retried after delayed some time.
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(1000);
    }

    @Test
    void propagateLastResponseWhenNextRetryIsAfterTimeout() {
        final WebClient client = client(RetryRule.builder()
                                                 .onServerErrorStatus()
                                                 .onException()
                                                 .thenBackoff(Backoff.fixed(10000000)));
        final AggregatedHttpResponse res = client.get("/service-unavailable").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void propagateLastResponseWhenExceedMaxAttempts() {
        final WebClient client = client(
                RetryRule.builder().onServerErrorStatus().onException().thenBackoff(Backoff.fixed(1)), 0, 0, 3);
        final AggregatedHttpResponse res = client.get("/service-unavailable").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void retryAfterOneYear() {
        final WebClient client = client(RetryRule.failsafe());

        // The response will be the last response whose headers contains HttpHeaderNames.RETRY_AFTER
        // because next retry is after timeout
        final ResponseHeaders headers = client.get("retry-after-one-year").aggregate().join().headers();
        assertThat(headers.status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(headers.get(HttpHeaderNames.RETRY_AFTER)).isNotNull();
    }

    @Test
    void retryOnResponseTimeout() {
        final Backoff backoff = Backoff.fixed(100);
        final RetryRule strategy =
                (ctx, cause) -> {
                    if (cause instanceof ResponseTimeoutException) {
                        return CompletableFuture.completedFuture(RetryDecision.retry(backoff));
                    }
                    return CompletableFuture.completedFuture(RetryDecision.noRetry());
                };

        final WebClient client = client(strategy, 0, 500, 100);
        final AggregatedHttpResponse res = client.get("/1sleep-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    void retryWithContentOnResponseTimeout() {
        final Backoff backoff = Backoff.fixed(100);
        final RetryRuleWithContent<HttpResponse> strategy =
                RetryRuleWithContent.<HttpResponse>onResponse((unused, response) -> {
                    return response.aggregate().thenApply(unused0 -> false);
                }).orElse(RetryRuleWithContent.onResponse((unused, response) -> {
                    return response.aggregate().thenApply(unused0 -> false);
                })).orElse(RetryRuleWithContent.<HttpResponse>onResponse((unused, response) -> {
                    return response.aggregate().thenApply(unused0 -> false);
                }).orElse(RetryRule.builder()
                                   .onException(ResponseTimeoutException.class)
                                   .thenBackoff(backoff)));
        final WebClient client = client(strategy, 0, 500, 100);
        final AggregatedHttpResponse res = client.get("/1sleep-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    void honorRetryMapping() {
        final Backoff backoff = Backoff.fixed(2000);
        final RetryConfigMapping<HttpResponse> mapping = RetryConfigMapping.of(
                (ctx, req) -> ctx.method() + "#" + ctx.path(),
                (ctx, req) -> {
                    if ("/500-always".equals(ctx.path())) {
                        return RetryConfig
                                .<HttpResponse>builder(RetryRule.builder()
                                                                .onStatus(HttpStatus.valueOf(500))
                                                                .thenBackoff(backoff))
                                .maxTotalAttempts(2).build();
                    } else if ("/501-always".equals(ctx.path())) {
                        return RetryConfig
                                .<HttpResponse>builder(RetryRule.builder()
                                                                .onStatus(HttpStatus.valueOf(501))
                                                                .thenBackoff(backoff))
                                .maxTotalAttempts(8).build();
                    } else {
                        return RetryConfig
                                .<HttpResponse>builder(RetryRule.builder()
                                                                .onStatus(HttpStatus.valueOf(400))
                                                                .thenBackoff(backoff))
                                .maxTotalAttempts(10).build();
                    }
                }
        );
        final WebClient client = client(mapping);

        Stopwatch stopwatch = Stopwatch.createStarted();
        assertThat(client.get("/500-always").aggregate().join().status())
                .isEqualTo(HttpStatus.valueOf(500));
        assertThat(stopwatch.elapsed()).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(6));

        stopwatch = Stopwatch.createStarted();
        assertThat(client.get("/501-always").aggregate().join().status())
                .isEqualTo(HttpStatus.valueOf(501));
        assertThat(stopwatch.elapsed()).isBetween(Duration.ofSeconds(14), Duration.ofSeconds(28));

        stopwatch = Stopwatch.createStarted();
        assertThat(client.get("/502-always").aggregate().join().status())
                .isEqualTo(HttpStatus.valueOf(502));
        assertThat(stopwatch.elapsed()).isBetween(Duration.ofSeconds(0), Duration.ofSeconds(2));
    }

    @Test
    void retryWithContentOnUnprocessedException() {
        final Backoff backoff = Backoff.fixed(2000);
        final RetryRuleWithContent<HttpResponse> strategy =
                RetryRuleWithContent.<HttpResponse>onResponse((unused, response) -> {
                    return response.aggregate().thenApply(unused0 -> false);
                }).orElse(RetryRuleWithContent.onResponse((unused, response) -> {
                    return response.aggregate().thenApply(unused0 -> false);
                })).orElse(RetryRuleWithContent.<HttpResponse>onResponse((unused, response) -> {
                    return response.aggregate().thenApply(unused0 -> false);
                }).orElse(RetryRule.builder()
                                   .onException(UnprocessedRequestException.class)
                                   .thenBackoff(backoff)));
        final Function<? super HttpClient, RetryingClient> retryingDecorator =
                RetryingClient.builder(strategy)
                              .maxTotalAttempts(5)
                              .newDecorator();

        try (ClientFactory clientFactory = ClientFactory.builder()
                                                        .options(RetryingClientTest.clientFactory.options())
                                                        .workerGroup(EventLoopGroups.newEventLoopGroup(2), true)
                                                        .connectTimeoutMillis(Long.MAX_VALUE)
                                                        .build()) {
            final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                              .factory(clientFactory)
                                              .responseTimeoutMillis(0)
                                              .decorator(LoggingClient.newDecorator())
                                              .decorator(retryingDecorator)
                                              .build();
            final Stopwatch stopwatch = Stopwatch.createStarted();
            assertThatThrownBy(() -> client.get("/unprocessed-exception").aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(UnprocessedRequestException.class);
            assertThat(stopwatch.elapsed()).isBetween(Duration.ofSeconds(7), Duration.ofSeconds(20));
        }
    }

    @ArgumentsSource(RetryStrategiesProvider.class)
    @ParameterizedTest
    void differentBackoffBasedOnStatus(RetryRule retryRule) {
        final WebClient client = client(retryRule);

        final Stopwatch sw = Stopwatch.createStarted();
        AggregatedHttpResponse res = client.get("/503-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isBetween((long) (10 * 0.9), (long) (1000 * 1.1));

        reqCount.set(0);
        sw.reset().start();

        res = client.get("/500-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo((long) (1000 * 0.9));
    }

    @Test
    void retryWithRequestBody() {
        final WebClient client = client(RetryRule.builder()
                                                 .onServerErrorStatus()
                                                 .onException()
                                                 .thenBackoff(Backoff.fixed(10)));
        final AggregatedHttpResponse res = client.post("/post-ping-pong", "bar").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("bar");
    }

    @Test
    void shouldGetExceptionWhenFactoryIsClosed() {
        final ClientFactory factory =
                ClientFactory.builder().workerGroup(EventLoopGroups.newEventLoopGroup(2), true).build();

        // Retry after 8000 which is slightly less than responseTimeoutMillis(10000).
        final Function<? super HttpClient, RetryingClient> retryingDecorator =
                RetryingClient.builder(RetryRule.builder()
                                                .onServerErrorStatus()
                                                .onException()
                                                .thenBackoff(Backoff.fixed(8000)))
                              .newDecorator();

        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(factory)
                                          .responseTimeoutMillis(10000)
                                          .decorator(retryingDecorator)
                                          .decorator(LoggingClient.newDecorator())
                                          .build();

        // There's no way to notice that the RetryingClient has scheduled the next retry.
        // The next retry will be after 8 seconds so closing the factory after 3 seconds should work.
        Executors.newSingleThreadScheduledExecutor().schedule(factory::close, 3, TimeUnit.SECONDS);

        // But it turned out that it's not working as expected in certain circumstance,
        // so we should handle all the cases.
        //
        // 1 - In RetryingClient, IllegalStateException("ClientFactory has been closed.") can be raised.
        // 2 - In HttpChannelPool, BootStrap.connect() can raise
        //     IllegalStateException("executor not accepting a task") wrapped by UnprocessedRequestException.
        // 3 - In HttpClientDelegate, addressResolverGroup.getResolver(eventLoop) can raise
        //     IllegalStateException("executor not accepting a task").
        //

        // Peel CompletionException first.
        Throwable t = peel(catchThrowable(() -> client.get("/service-unavailable").aggregate().join()));
        if (t instanceof UnprocessedRequestException) {
            final Throwable cause = t.getCause();
            assertThat(cause).isInstanceOf(IllegalStateException.class);
            t = cause;
        }
        assertThat(t).isInstanceOf(IllegalStateException.class)
                     .satisfies(cause -> assertThat(cause.getMessage()).matches(
                             "(?i).*(factory has been closed|not accepting a task).*"));
    }

    @Test
    void doNotRetryWhenResponseIsAborted() throws Exception {
        final List<Throwable> abortCauses =
                Arrays.asList(null, new IllegalStateException("abort stream with a specified cause"));
        for (Throwable abortCause : abortCauses) {
            final AtomicReference<ClientRequestContext> context = new AtomicReference<>();
            final WebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(RetryingClient.newDecorator(retryAlways))
                             .decorator((delegate, ctx, req) -> {
                                 context.set(ctx);
                                 return delegate.execute(ctx, req);
                             })
                             .build();
            final HttpResponse httpResponse = client.get("/response-abort");
            if (abortCause == null) {
                httpResponse.abort();
            } else {
                httpResponse.abort(abortCause);
            }

            final RequestLog log = context.get().log().whenComplete().join();
            assertThat(responseAbortServiceCallCounter.get()).isOne();
            assertThat(log.requestCause()).isNull();
            if (abortCause == null) {
                assertThat(log.responseCause()).isExactlyInstanceOf(AbortedStreamException.class);
            } else {
                assertThat(log.responseCause()).isSameAs(abortCause);
            }

            // Sleep 3 more seconds to check if there was another retry.
            TimeUnit.SECONDS.sleep(3);
            assertThat(responseAbortServiceCallCounter.get()).isOne();
            responseAbortServiceCallCounter.decrementAndGet();
        }
    }

    @Test
    void retryDoNotStopUntilGetResponseWhenSubscriberCancel() {
        final WebClient client = client(retryAlways);
        client.get("/subscriber-cancel").subscribe(
                new Subscriber<HttpObject>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        s.cancel(); // Cancel as soon as getting the subscription.
                    }

                    @Override
                    public void onNext(HttpObject httpObject) {}

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onComplete() {}
                });

        await().untilAsserted(() -> assertThat(subscriberCancelServiceCallCounter.get()).isEqualTo(3));
    }

    @Test
    void doNotRetryWhenRequestIsAborted() throws Exception {
        final List<Throwable> abortCauses =
                Arrays.asList(null, new IllegalStateException("abort stream with a specified cause"));
        for (Throwable abortCause : abortCauses) {
            final AtomicReference<ClientRequestContext> context = new AtomicReference<>();
            final WebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(RetryingClient.newDecorator(retryAlways))
                             .decorator((delegate, ctx, req) -> {
                                 context.set(ctx);
                                 return delegate.execute(ctx, req);
                             })
                             .build();

            final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.GET, "/request-abort");
            req.write(HttpData.ofUtf8("I'm going to abort this request"));
            if (abortCause == null) {
                req.abort();
            } else {
                req.abort(abortCause);
            }
            client.execute(req).aggregate();

            TimeUnit.SECONDS.sleep(1);
            // No request is made.
            assertThat(responseAbortServiceCallCounter.get()).isZero();
            final RequestLog log = context.get().log().whenComplete().join();
            if (abortCause == null) {
                assertThat(log.requestCause()).isExactlyInstanceOf(AbortedStreamException.class);
                assertThat(log.responseCause()).isExactlyInstanceOf(AbortedStreamException.class);
            } else {
                assertThat(log.requestCause()).isSameAs(abortCause);
                assertThat(log.responseCause()).isSameAs(abortCause);
            }
        }
    }

    @Test
    void exceptionInDecorator() {
        final AtomicInteger retryCounter = new AtomicInteger();
        final RetryRule strategy = (ctx, cause) -> {
            retryCounter.incrementAndGet();
            return CompletableFuture.completedFuture(RetryDecision.retry(Backoff.withoutDelay()));
        };
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator((delegate, ctx, req) -> {
                                              throw new AnticipatedException();
                                          })
                                          .decorator(RetryingClient.newDecorator(strategy, 5))
                                          .build();

        assertThatThrownBy(() -> client.get("/").aggregate().join())
                .hasCauseExactlyInstanceOf(AnticipatedException.class);
        assertThat(retryCounter.get()).isEqualTo(5);
    }

    @Test
    void exceptionInRule() {
        final IllegalStateException exception = new IllegalStateException("foo");
        final RetryRule rule = (ctx, cause) -> {
            throw exception;
        };

        final WebClient client = client(rule);
        assertThatThrownBy(client.get("/").aggregate()::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseReference(exception);
    }

    @Test
    void exceptionInRuleWithContent() {
        final IllegalStateException exception = new IllegalStateException("bar");
        final RetryRuleWithContent<HttpResponse> rule = (ctx, res, cause) -> {
            throw exception;
        };

        final WebClient client = client(rule, 10000, 0, 100);
        assertThatThrownBy(client.get("/").aggregate()::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseReference(exception);
    }

    @Test
    void useSameEventLoopWhenAggregate() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<EventLoop> eventLoop = new AtomicReference<>();
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             eventLoop.set(ctx.eventLoop());
                             return delegate.execute(ctx, req);
                         })
                         .decorator(RetryingClient.newDecorator(RetryRule.failsafe(), 2))
                         .build();
        client.get("/503-then-success").aggregate().whenComplete((unused, cause) -> {
            assertThat(eventLoop.get().inEventLoop()).isTrue();
            latch.countDown();
        });
        latch.await();
    }

    private WebClient client(RetryRule retryRule) {
        return client(retryRule, 10000, 0, 100);
    }

    private WebClient client(RetryConfigMapping<HttpResponse> mapping) {
        final Function<? super HttpClient, RetryingClient> retryingDecorator =
                RetryingClient.builder(mapping)
                              .useRetryAfter(true)
                              .newDecorator();

        return WebClient.builder(server.httpUri())
                        .factory(clientFactory)
                        .responseTimeoutMillis(0)
                        .decorator(retryingDecorator)
                        .build();
    }

    private WebClient client(RetryRule retryRule, long responseTimeoutMillis,
                             long responseTimeoutForEach, int maxTotalAttempts) {
        final Function<? super HttpClient, RetryingClient> retryingDecorator =
                RetryingClient.builder(retryRule)
                              .responseTimeoutMillisForEachAttempt(responseTimeoutForEach)
                              .useRetryAfter(true)
                              .maxTotalAttempts(maxTotalAttempts)
                              .newDecorator();

        return WebClient.builder(server.httpUri())
                        .factory(clientFactory)
                        .responseTimeoutMillis(responseTimeoutMillis)
                        .decorator(retryingDecorator)
                        .build();
    }

    private WebClient client(RetryRuleWithContent<HttpResponse> retryRuleWithContent,
                             long responseTimeoutMillis,
                             long responseTimeoutForEach, int maxTotalAttempts) {
        final Function<? super HttpClient, RetryingClient> retryingDecorator =
                RetryingClient.builder(retryRuleWithContent)
                              .responseTimeoutMillisForEachAttempt(responseTimeoutForEach)
                              .useRetryAfter(true)
                              .maxTotalAttempts(maxTotalAttempts)
                              .newDecorator();

        return WebClient.builder(server.httpUri())
                        .factory(clientFactory)
                        .responseTimeoutMillis(responseTimeoutMillis)
                        .decorator(LoggingClient.newDecorator())
                        .decorator(retryingDecorator)
                        .build();
    }

    private static class RetryIfContentMatch implements RetryRuleWithContent<HttpResponse> {
        private final String retryContent;
        private final RetryDecision decision = RetryDecision.retry(Backoff.fixed(100));

        RetryIfContentMatch(String retryContent) {
            this.retryContent = retryContent;
        }

        @Override
        public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx,
                                                          @Nullable HttpResponse response,
                                                          @Nullable Throwable cause) {
            final CompletableFuture<AggregatedHttpResponse> future = response.aggregate();
            return future.handle((aggregatedResponse, unused) -> {
                if (aggregatedResponse != null &&
                    aggregatedResponse.contentUtf8().equalsIgnoreCase(retryContent)) {
                    return decision;
                }
                return null;
            });
        }
    }

    private static final class RetryStrategiesProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final Backoff backoffOn503 = Backoff.fixed(10).withMaxAttempts(2);
            final Backoff backoffOn500 = Backoff.fixed(1000).withMaxAttempts(2);

            final RetryRule retryRule =
                    RetryRule.of(RetryRule.builder()
                                          .onStatus(HttpStatus.SERVICE_UNAVAILABLE)
                                          .thenBackoff(backoffOn503),
                                 RetryRule.builder()
                                          .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                          .thenBackoff(backoffOn500));

            return Stream.of(retryRule).map(Arguments::of);
        }
    }
}

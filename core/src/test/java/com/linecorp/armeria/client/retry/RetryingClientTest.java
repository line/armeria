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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.junit.AfterClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
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
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.netty.channel.EventLoop;

public class RetryingClientTest {

    // use different eventLoop from server's so that clients don't hang when the eventLoop in server hangs
    private static final ClientFactory clientFactory =
            ClientFactory.builder().workerGroup(EventLoopGroups.newEventLoopGroup(2), true).build();

    private static final RetryStrategy retryAlways =
            (ctx, cause) -> CompletableFuture.completedFuture(Backoff.fixed(500));

    private final AtomicInteger responseAbortServiceCallCounter = new AtomicInteger();

    private final AtomicInteger requestAbortServiceCallCounter = new AtomicInteger();

    private final AtomicInteger subscriberCancelServiceCallCounter = new AtomicInteger();

    @AfterClass
    public static void destroy() {
        clientFactory.close();
    }

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Rule
    public final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/retry-content", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

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

            sb.service("/500-then-success", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

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
                final AtomicInteger reqCount = new AtomicInteger();

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

            sb.service("/retry-after-1-second", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

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
                final AtomicInteger reqCount = new AtomicInteger();

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
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        TimeUnit.MILLISECONDS.sleep(1000);
                        return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
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

    @Test
    public void retryWhenContentMatched() {
        final Function<? super HttpClient, RetryingClient> retryingDecorator =
                RetryingClient.builder(new RetryIfContentMatch("Need to retry"))
                              .contentPreviewLength(1024)
                              .newDecorator();
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(retryingDecorator)
                                          .build();

        final AggregatedHttpResponse res = client.get("/retry-content").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    public void retryWhenStatusMatched() {
        final WebClient client = client(RetryStrategy.onServerErrorStatus());
        final AggregatedHttpResponse res = client.get("/503-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    public void disableResponseTimeout() {
        final WebClient client = client(RetryStrategy.onServerErrorStatus(), 0, 0, 100);
        final AggregatedHttpResponse res = client.get("/503-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
        // response timeout did not happen.
    }

    @Test
    public void respectRetryAfter() {
        final WebClient client = client(RetryStrategy.onServerErrorStatus());
        final Stopwatch sw = Stopwatch.createStarted();

        final AggregatedHttpResponse res = client.get("/retry-after-1-second").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (TimeUnit.SECONDS.toMillis(1) * 0.9));
    }

    @Test
    public void respectRetryAfterWithHttpDate() {
        final WebClient client = client(RetryStrategy.onServerErrorStatus());

        final Stopwatch sw = Stopwatch.createStarted();
        final AggregatedHttpResponse res = client.get("/retry-after-with-http-date").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        // Since ZonedDateTime doesn't express exact time,
        // just check out whether it is retried after delayed some time.
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(1000);
    }

    @Test
    public void propagateLastResponseWhenNextRetryIsAfterTimeout() {
        final WebClient client = client(RetryStrategy.onServerErrorStatus(Backoff.fixed(10000000)));
        final AggregatedHttpResponse res = client.get("/service-unavailable").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    public void propagateLastResponseWhenExceedMaxAttempts() {
        final WebClient client = client(RetryStrategy.onServerErrorStatus(Backoff.fixed(1)), 0, 0, 3);
        final AggregatedHttpResponse res = client.get("/service-unavailable").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    public void retryAfterOneYear() {
        final WebClient client = client(RetryStrategy.onServerErrorStatus());

        // The response will be the last response whose headers contains HttpHeaderNames.RETRY_AFTER
        // because next retry is after timeout
        final ResponseHeaders headers = client.get("retry-after-one-year").aggregate().join().headers();
        assertThat(headers.status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(headers.get(HttpHeaderNames.RETRY_AFTER)).isNotNull();
    }

    @Test
    public void retryOnResponseTimeout() {
        final Backoff backoff = Backoff.fixed(100);
        final RetryStrategy strategy =
                (ctx, cause) -> {
                    if (cause instanceof ResponseTimeoutException) {
                        return CompletableFuture.completedFuture(backoff);
                    }
                    return CompletableFuture.completedFuture(null);
                };

        final WebClient client = client(strategy, 0, 500, 100);
        final AggregatedHttpResponse res = client.get("/1sleep-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    public void differentBackoffBasedOnStatus() {
        final WebClient client = client(RetryStrategy.onStatus(statusBasedBackoff()));

        final Stopwatch sw = Stopwatch.createStarted();
        AggregatedHttpResponse res = client.get("/503-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isBetween((long) (10 * 0.9), (long) (1000 * 1.1));

        sw.reset().start();
        res = client.get("/500-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo((long) (1000 * 0.9));
    }

    private static BiFunction<HttpStatus, Throwable, Backoff> statusBasedBackoff() {
        return new BiFunction<HttpStatus, Throwable, Backoff>() {
            private final Backoff backoffOn503 = Backoff.fixed(10).withMaxAttempts(2);
            private final Backoff backoffOn500 = Backoff.fixed(1000).withMaxAttempts(2);

            @Nullable
            @Override
            public Backoff apply(HttpStatus httpStatus, Throwable unused) {
                if (httpStatus == HttpStatus.SERVICE_UNAVAILABLE) {
                    return backoffOn503;
                }
                if (httpStatus == HttpStatus.INTERNAL_SERVER_ERROR) {
                    return backoffOn500;
                }
                return null;
            }
        };
    }

    @Test
    public void retryWithRequestBody() {
        final WebClient client = client(RetryStrategy.onServerErrorStatus(Backoff.fixed(10)));
        final AggregatedHttpResponse res = client.post("/post-ping-pong", "bar").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("bar");
    }

    @Test
    public void shouldGetExceptionWhenFactoryIsClosed() {
        final ClientFactory factory =
                ClientFactory.builder().workerGroup(EventLoopGroups.newEventLoopGroup(2), true).build();

        // Retry after 8000 which is slightly less than responseTimeoutMillis(10000).
        final Function<? super HttpClient, RetryingClient> retryingDecorator =
                RetryingClient.builder(RetryStrategy.onServerErrorStatus(Backoff.fixed(8000)))
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
    public void doNotRetryWhenResponseIsAborted() throws Exception {
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
    public void retryDoNotStopUntilGetResponseWhenSubscriberCancel() {
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
    public void doNotRetryWhenRequestIsAborted() throws Exception {
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
            client.execute(req);

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
    public void exceptionInDecorator() {
        final AtomicInteger retryCounter = new AtomicInteger();
        final RetryStrategy strategy = (ctx, cause) -> {
            retryCounter.incrementAndGet();
            return CompletableFuture.completedFuture(Backoff.withoutDelay());
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
    public void useSameEventLoopWhenAggregate() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<EventLoop> eventLoop = new AtomicReference<>();
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator((delegate, ctx, req) -> {
                                              eventLoop.set(ctx.eventLoop());
                                              return delegate.execute(ctx, req);
                                          })
                                          .decorator(RetryingClient.newDecorator(
                                                  RetryStrategy.onServerErrorStatus(), 2))
                                          .build();
        client.get("/503-then-success").aggregate().whenComplete((unused, cause) -> {
            assertThat(eventLoop.get().inEventLoop()).isTrue();
            latch.countDown();
        });
        latch.await();
    }

    private WebClient client(RetryStrategy strategy) {
        return client(strategy, 10000, 0, 100);
    }

    private WebClient client(RetryStrategy strategy, long responseTimeoutMillis,
                             long responseTimeoutForEach, int maxTotalAttempts) {
        final Function<? super HttpClient, RetryingClient> retryingDecorator =
                RetryingClient.builder(strategy)
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

    private static class RetryIfContentMatch implements RetryStrategyWithContent<HttpResponse> {
        private final String retryContent;
        private final Backoff backoffOnContent = Backoff.fixed(100);

        RetryIfContentMatch(String retryContent) {
            this.retryContent = retryContent;
        }

        @Override
        public CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, HttpResponse response) {
            final CompletableFuture<AggregatedHttpResponse> future = response.aggregate();
            return future.handle((aggregatedResponse, unused) -> {
                if (aggregatedResponse != null &&
                    aggregatedResponse.contentUtf8().equalsIgnoreCase(retryContent)) {
                    return backoffOnContent;
                }
                return null;
            });
        }
    }
}

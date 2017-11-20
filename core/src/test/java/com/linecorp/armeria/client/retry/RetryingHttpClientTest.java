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

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.server.ServerRule;

public class RetryingHttpClientTest {

    private final int oneSecForRetryAfter = 1;

    private static ClientFactory clientFactory;

    @BeforeClass
    public static void init() {
        // use different eventLoop from server's so that clients don't hang when the eventLoop in server hangs
        clientFactory = new ClientFactoryBuilder()
                .workerGroup(EventLoopGroups.newEventLoopGroup(2), true).build();
    }

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
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 2) {
                        res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Need to retry");
                    } else {
                        res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }
            });

            sb.service("/500-then-success", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        res.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                    } else {
                        res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }
            });

            sb.service("/503-then-success", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        res.respond(HttpStatus.SERVICE_UNAVAILABLE);
                    } else {
                        res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }
            });

            sb.service("/retry-after-1-second", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        res.write(HttpHeaders.of(HttpStatus.SERVICE_UNAVAILABLE)
                                             .setInt(HttpHeaderNames.RETRY_AFTER, oneSecForRetryAfter));
                        res.close();
                    } else {
                        res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }
            });

            sb.service("/retry-after-with-http-date", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        res.write(HttpHeaders.of(HttpStatus.SERVICE_UNAVAILABLE)
                                             .setTimeMillis(HttpHeaderNames.RETRY_AFTER,
                                                            Duration.ofSeconds(3).toMillis() +
                                                            System.currentTimeMillis()));
                        res.close();
                    } else {
                        res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }
            });

            sb.service("/retry-after-one-year", new AbstractHttpService() {

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    res.write(HttpHeaders.of(HttpStatus.SERVICE_UNAVAILABLE)
                                         .setTimeMillis(HttpHeaderNames.RETRY_AFTER,
                                                        Duration.ofDays(365).toMillis() +
                                                        System.currentTimeMillis()));
                    res.close();
                }
            });

            sb.service("/sleep-after-1-response", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqCount.getAndIncrement() % 2 == 0) {
                        res.respond(HttpStatus.SERVICE_UNAVAILABLE);
                    } else {
                        TimeUnit.MILLISECONDS.sleep(1500);
                    }
                }
            });

            sb.service("/service-unavailable", new AbstractHttpService() {

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    res.respond(HttpStatus.SERVICE_UNAVAILABLE);
                }
            });

            sb.service("/get-post", new AbstractHttpService() {
                final AtomicInteger reqGetCount = new AtomicInteger();
                final AtomicInteger reqPostCount = new AtomicInteger();

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqGetCount.getAndIncrement() < 1) {
                        res.respond(HttpStatus.SERVICE_UNAVAILABLE);
                    } else {
                        TimeUnit.MILLISECONDS.sleep(1000);
                        res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }

                @Override
                protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqPostCount.getAndIncrement() < 1) {
                        res.respond(HttpStatus.SERVICE_UNAVAILABLE);
                    } else {
                        TimeUnit.MILLISECONDS.sleep(1000);
                        res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }
            });

            sb.service("/1sleep-then-success", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        TimeUnit.MILLISECONDS.sleep(1000);
                        res.respond(HttpStatus.SERVICE_UNAVAILABLE);
                    } else {
                        res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }
            });

            sb.service("/post-ping-pong", new AbstractHttpService() {
                final AtomicInteger reqPostCount = new AtomicInteger();

                @Override
                protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    req.aggregate().handle(voidFunction((message, thrown) -> {
                        if (reqPostCount.getAndIncrement() < 1) {
                            res.respond(HttpStatus.SERVICE_UNAVAILABLE);
                        } else {
                            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                        message.content().toStringUtf8());
                        }
                    }));
                }
            });
        }
    };

    @Test
    public void retryWhenContentMatched() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy = new RetryOnContent("Need to retry");
        final HttpClient client =
                new HttpClientBuilder(server.uri("/")).factory(clientFactory).decorator(
                        RetryingHttpClient.newDecorator(strategy)).build();

        final AggregatedHttpMessage res = client.get("/retry-content").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
    }

    private static class RetryOnContent implements RetryStrategy<HttpRequest, HttpResponse> {
        private final String retryContent;
        private final Backoff backoffOnContent = Backoff.fixed(100);

        RetryOnContent(String retryContent) {
            this.retryContent = retryContent;
        }

        @Override
        public CompletableFuture<Optional<Backoff>> shouldRetry(HttpRequest request, HttpResponse response) {
            final CompletableFuture<AggregatedHttpMessage> future = response.aggregate();
            return future.handle((message, unused) -> {
                                     if (message != null &&
                                         message.content().toStringUtf8().equalsIgnoreCase(retryContent)) {
                                         return Optional.of(backoffOnContent);
                                     } else {
                                         return Optional.empty();
                                     }
                                 }
            );
        }
    }

    @Test
    public void retryWhenStatusMatched() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy = RetryStrategy.onServerErrorStatus();
        final HttpClient client =
                new HttpClientBuilder(server.uri("/"))
                        .factory(clientFactory).decorator(RetryingHttpClient.newDecorator(strategy)).build();

        final AggregatedHttpMessage res = client.get("/503-then-success").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    public void respectRetryAfter() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy = RetryStrategy.onServerErrorStatus();
        final HttpClient client = retryingHttpClientOf(10000, strategy);

        final Stopwatch sw = Stopwatch.createStarted();

        final AggregatedHttpMessage res = client.get("/retry-after-1-second").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (TimeUnit.SECONDS.toMillis(oneSecForRetryAfter) * 0.9));
    }

    private HttpClient retryingHttpClientOf(long responseTimeoutMillis,
                                            RetryStrategy<HttpRequest, HttpResponse> strategy) {
        return new HttpClientBuilder(server.uri("/"))
                .factory(clientFactory).defaultResponseTimeoutMillis(responseTimeoutMillis)
                .decorator(new RetryingHttpClientBuilder(strategy).useRetryAfter(true)
                                                                  .defaultMaxAttempts(100).newDecorator())
                .build();
    }

    @Test
    public void respectRetryAfterWithHttpDate() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy = RetryStrategy.onServerErrorStatus();
        final HttpClient client = retryingHttpClientOf(10000, strategy);

        final Stopwatch sw = Stopwatch.createStarted();
        final AggregatedHttpMessage res = client.get("/retry-after-with-http-date").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");

        // Since ZonedDateTime doesn't express exact time,
        // just check out whether it is retried after delayed some time.
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(1000);
    }

    @Test
    public void retryAfterOneYear() {
        long responseTimeoutMillis = 1000;
        final HttpClient client = retryingHttpClientOf(
                responseTimeoutMillis, RetryStrategy.onServerErrorStatus());

        final Stopwatch sw = Stopwatch.createStarted();
        assertThatThrownBy(() -> client.get("/retry-after-one-year").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);

        // Retry after is limited by response time out which is 1 second in this case.
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(
                (long) (responseTimeoutMillis * 1.1));
    }

    @Test
    public void timeoutWhenServerDoseNotResponse() {
        final long responseTimeoutMillis = 1000;
        final RetryStrategy<HttpRequest, HttpResponse> strategy = RetryStrategy.onServerErrorStatus();
        HttpClient client = retryingHttpClientOf(responseTimeoutMillis, strategy);

        final Stopwatch sw = Stopwatch.createStarted();
        final CompletableFuture<AggregatedHttpMessage> res1 = client.get("/sleep-after-1-response").aggregate();
        assertThatThrownBy(res1::join).hasCauseInstanceOf(ResponseTimeoutException.class);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (responseTimeoutMillis * 0.9));

        final long responseTimeoutMillisForEachAttempt = 1000;
        client = new HttpClientBuilder(server.uri("/"))
                .factory(clientFactory).defaultResponseTimeoutMillis(10000)
                .decorator(RetryingHttpClient.newDecorator(strategy, 100, responseTimeoutMillisForEachAttempt))
                .build();

        // second request
        sw.reset();
        sw.start();
        final CompletableFuture<AggregatedHttpMessage> res2 = client.get("/sleep-after-1-response").aggregate();
        assertThatThrownBy(res2::join).hasCauseInstanceOf(ResponseTimeoutException.class);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (responseTimeoutMillisForEachAttempt * 0.9));
    }

    @Test
    public void timeoutWhenServerSendServiceUnavailable() {
        long responseTimeoutMillis = 1000;
        final HttpClient client = retryingHttpClientOf(
                responseTimeoutMillis, RetryStrategy.onServerErrorStatus(Backoff.fixed(100)));

        final Stopwatch sw = Stopwatch.createStarted();
        assertThatThrownBy(() -> client.get("/service-unavailable").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (responseTimeoutMillis * 0.9));
    }

    @Test
    public void consecutiveRequests() {
        long responseTimeoutMillis = 500;
        final HttpClient client = retryingHttpClientOf(
                responseTimeoutMillis, RetryStrategy.onServerErrorStatus());

        final Stopwatch sw = Stopwatch.createStarted();
        assertThatThrownBy(() -> client.get("/service-unavailable").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(
                (long) (responseTimeoutMillis * 1.1));

        // second request
        sw.reset();
        sw.start();
        assertThatThrownBy(() -> client.get("/service-unavailable").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(
                (long) (responseTimeoutMillis * 1.1));
    }

    @Test
    public void disableResponseTimeout() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy = new RetryOnContent("Need to retry");

        final HttpClient client =
                new HttpClientBuilder(server.uri("/"))
                        .factory(clientFactory).defaultResponseTimeoutMillis(0)
                        .decorator(RetryingHttpClient.newDecorator(strategy, 100, 0)).build();

        final AggregatedHttpMessage res = client.get("/retry-content").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
        // response timeout did not happen.
    }

    @Test
    public void differentResponseTimeout() {
        final Backoff backoffOnServerError = Backoff.fixed(10);
        final RetryStrategy<HttpRequest, HttpResponse> strategy =
                RetryStrategy.onServerErrorStatus(backoffOnServerError);
        final HttpClient client =
                new HttpClientBuilder(server.uri("/"))
                        .factory(clientFactory)
                        .decorator(RetryingHttpClient.newDecorator(strategy))
                        .decorator((delegate, ctx, req) -> {
                            if (req.method() == HttpMethod.GET) {
                                ctx.setResponseTimeoutMillis(50);
                            } else {
                                ctx.setResponseTimeoutMillis(10000);
                            }
                            return delegate.execute(ctx, req);
                        }).build();

        assertThatThrownBy(() -> client.get("/get-post").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        final AggregatedHttpMessage res = client.post("/get-post", "foo").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    public void retryOnResponseTimeout() {
        RetryStrategy<HttpRequest, HttpResponse> strategy = new RetryStrategy<HttpRequest, HttpResponse>() {
            final Backoff backoff = Backoff.fixed(100);
            @Override
            public CompletableFuture<Optional<Backoff>> shouldRetry(HttpRequest request,
                                                                    HttpResponse response) {
                return response.aggregate().handle((result, cause) -> {
                    if (cause instanceof ResponseTimeoutException) {
                        return Optional.of(backoff);
                    }
                    return Optional.empty();
                });
            }
        };

        final HttpClient client =
                new HttpClientBuilder(server.uri("/"))
                        .factory(clientFactory).defaultResponseTimeoutMillis(0)
                        .decorator(RetryingHttpClient.newDecorator(strategy, 100, 500)).build();
        final AggregatedHttpMessage res = client.get("/1sleep-then-success").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    public void differentBackoffBasedOnStatus() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy = RetryStrategy.onStatus(statusBasedBackoff());
        final HttpClient client = new HttpClientBuilder(server.uri("/"))
                .factory(clientFactory).decorator(RetryingHttpClient.newDecorator(strategy)).build();

        final Stopwatch sw = Stopwatch.createStarted();
        AggregatedHttpMessage res = client.get("/503-then-success").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isBetween((long) (10 * 0.9), (long) (10000 * 0.9));
        // second request
        sw.reset();
        sw.start();
        res = client.get("/500-then-success").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo((long) (1000 * 0.9));
    }

    private Function<HttpStatus, Optional<Backoff>> statusBasedBackoff() {
        return new Function<HttpStatus, Optional<Backoff>>() {
            private final Backoff backoffOn503 = Backoff.fixed(10).withMaxAttempts(2);
            private final Backoff backoffOn500 = Backoff.fixed(1000).withMaxAttempts(2);

            @Override
            public Optional<Backoff> apply(HttpStatus httpStatus) {
                if (httpStatus == HttpStatus.SERVICE_UNAVAILABLE) {
                    return Optional.of(backoffOn503);
                } else if (httpStatus == HttpStatus.INTERNAL_SERVER_ERROR) {
                    return Optional.of(backoffOn500);
                } else {
                    return Optional.empty();
                }
            }
        };
    }

    @Test
    public void retryWithRequestBody() {
        final Backoff backoffOnServerError = Backoff.fixed(10);
        final RetryStrategy<HttpRequest, HttpResponse> strategy =
                RetryStrategy.onServerErrorStatus(backoffOnServerError);
        final HttpClient client =
                new HttpClientBuilder(server.uri("/"))
                        .factory(clientFactory)
                        .decorator(RetryingHttpClient.newDecorator(strategy))
                        .build();

        final AggregatedHttpMessage res = client.post("/post-ping-pong", "bar").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("bar");
    }
}

/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

            sb.service("/503-then-success", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 2) {
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
                    if (reqCount.getAndIncrement() < 1) {
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
        }
    };

    @Test
    public void retryWhenContentMatched() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy = new RetryOnContent("Need to retry");
        final HttpClient client = new HttpClientBuilder(server.uri("/")).factory(clientFactory)
                .decorator(RetryingHttpClient.newDecorator(strategy, () -> Backoff.fixed(100))).build();

        final AggregatedHttpMessage res = client.get("/retry-content").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
    }

    private static class RetryOnContent implements RetryStrategy<HttpRequest, HttpResponse> {
        private final String retryContent;

        RetryOnContent(String retryContent) {
            this.retryContent = retryContent;
        }

        @Override
        public CompletableFuture<Boolean> shouldRetry(HttpRequest request, HttpResponse response) {
            final CompletableFuture<AggregatedHttpMessage> future = response.aggregate();
            return future.handle((message, thrown) ->
                                         message != null &&
                                         message.content().toStringUtf8().equalsIgnoreCase(retryContent)
            );
        }
    }

    @Test
    public void retryWhenStatusMatched() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy =
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE);
        final HttpClient client = new HttpClientBuilder(server.uri("/")).factory(clientFactory)
                .decorator(RetryingHttpClient.newDecorator(strategy, () -> Backoff.fixed(100))).build();

        final AggregatedHttpMessage res = client.get("/503-then-success").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    public void respectRetryAfter() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy =
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE);
        final HttpClient client = retryingHttpClientOf(10000, strategy);

        final Stopwatch sw = Stopwatch.createStarted();

        final AggregatedHttpMessage res = client.get("/retry-after-1-second").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (TimeUnit.SECONDS.toMillis(oneSecForRetryAfter) * 0.9));
    }

    private HttpClient retryingHttpClientOf(long responseTimeoutMillis,
                                            RetryStrategy<HttpRequest, HttpResponse> strategy) {
        return new HttpClientBuilder(server.uri("/")).factory(clientFactory)
                .defaultResponseTimeoutMillis(responseTimeoutMillis)
                .decorator(new RetryingHttpClientBuilder(strategy)
                                   .backoffSupplier(() -> Backoff.fixed(100).withMaxAttempts(1000))
                                   .useRetryAfter(true).newDecorator())
                .build();
    }

    @Test
    public void respectRetryAfterWithHttpDate() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy =
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE);
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
        final RetryStrategyWrapper strategy = new RetryStrategyWrapper(
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE));
        final HttpClient client = retryingHttpClientOf(responseTimeoutMillis, strategy);

        final Stopwatch sw = Stopwatch.createStarted();
        assertThatThrownBy(() -> client.get("/retry-after-one-year").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);

        // Retry after is limited by response time out which is 1 second in this case.
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (responseTimeoutMillis * 0.9));
    }

    @Test
    public void timeoutWhenServerDoseNotResponse() {
        long responseTimeoutMillis = 1000;
        final RetryStrategyWrapper strategy = new RetryStrategyWrapper(
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE));
        final HttpClient client = retryingHttpClientOf(responseTimeoutMillis, strategy);

        final Stopwatch sw = Stopwatch.createStarted();
        assertThatThrownBy(() -> client.get("/sleep-after-1-response").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (responseTimeoutMillis * 0.9));
    }

    @Test
    public void timeoutWhenServerSendServiceUnavailable() {
        long responseTimeoutMillis = 1000;
        final RetryStrategyWrapper strategy = new RetryStrategyWrapper(
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE));
        final HttpClient client = retryingHttpClientOf(responseTimeoutMillis, strategy);

        final Stopwatch sw = Stopwatch.createStarted();
        assertThatThrownBy(() -> client.get("/service-unavailable").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (responseTimeoutMillis * 0.9));
    }

    @Test
    public void consecutiveRequests() {
        long responseTimeoutMillis = 500;
        final RetryStrategyWrapper strategy = new RetryStrategyWrapper(
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE));
        final HttpClient client = retryingHttpClientOf(responseTimeoutMillis, strategy);

        final Stopwatch sw = Stopwatch.createStarted();
        assertThatThrownBy(() -> client.get("/service-unavailable").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (responseTimeoutMillis * 0.9));

        // second request
        sw.reset();
        sw.start();
        assertThatThrownBy(() -> client.get("/service-unavailable").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(
                (long) (responseTimeoutMillis * 0.9));
    }

    @Test
    public void disableResponseTimeout() {
        final RetryStrategy<HttpRequest, HttpResponse> strategy = new RetryOnContent("Need to retry");

        final HttpClient client = new HttpClientBuilder(server.uri("/")).factory(clientFactory)
                .defaultResponseTimeoutMillis(0) // disable response timeout
                .decorator(RetryingHttpClient.newDecorator(strategy, () -> Backoff.fixed(100))).build();

        final AggregatedHttpMessage res = client.get("/retry-content").aggregate().join();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
        // response timeout did not happen.
    }

    @Test
    public void differentResponseTimeout() {
        final RetryStrategyWrapper strategy = new RetryStrategyWrapper(
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE));
        final HttpClient client = new HttpClientBuilder(server.uri("/")).factory(clientFactory)
                .decorator(RetryingHttpClient
                                   .newDecorator(strategy, () -> Backoff.fixed(100).withMaxAttempts(500)))
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

    private static class RetryStrategyWrapper implements RetryStrategy<HttpRequest, HttpResponse> {

        private final RetryStrategy<HttpRequest, HttpResponse> delegate;

        RetryStrategyWrapper(RetryStrategy<HttpRequest, HttpResponse> delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<Boolean> shouldRetry(HttpRequest request, HttpResponse response) {
            return delegate.shouldRetry(request, response);
        }

        @Override
        public boolean shouldRetry(HttpRequest request, Throwable cause) {
            if (cause != null) {
                if (cause instanceof ResponseTimeoutException) {
                    return false;
                }
            }
            return true;
        }
    }
}

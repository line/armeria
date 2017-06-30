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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.server.ServerRule;

public class RetryingHttpClientTest {

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

            sb.service("/retry-after", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        res.write(HttpHeaders.of(HttpStatus.SERVICE_UNAVAILABLE)
                                             .setInt(HttpHeaderNames.RETRY_AFTER, 3));
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
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    if (reqCount.getAndIncrement() < 1) {
                        res.write(HttpHeaders.of(HttpStatus.SERVICE_UNAVAILABLE)
                                             .setTimeMillis(HttpHeaderNames.RETRY_AFTER,
                                                            Duration.ofDays(365).toMillis() +
                                                            System.currentTimeMillis()));
                        res.close();
                    } else {
                        res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Succeeded after retry");
                    }
                }
            });
        }
    };

    @Test
    public void retryWhenContentMatched() throws Exception {
        final RetryStrategy<HttpRequest, HttpResponse> strategy = new RetryOnContent("Need to retry");

        final HttpClient client = new ClientBuilder(server.uri(SerializationFormat.NONE, "/"))
                .decorator(HttpRequest.class, HttpResponse.class,
                           RetryingHttpClient.newDecorator(strategy))
                .build(HttpClient.class);

        final AggregatedHttpMessage res = client.get("/retry-content").aggregate().get();
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
    public void retryWhenStatusMatched() throws Exception {
        final RetryStrategy<HttpRequest, HttpResponse> strategy =
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE);
        final HttpClient client = new ClientBuilder(server.uri(SerializationFormat.NONE, "/"))
                .decorator(HttpRequest.class, HttpResponse.class,
                           RetryingHttpClient.newDecorator(strategy, Backoff::withoutDelay))
                .build(HttpClient.class);

        final AggregatedHttpMessage res = client.get("/503-then-success").aggregate().get();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
    }

    @Test
    public void respectRetryAfter() throws Exception {
        final RetryStrategy<HttpRequest, HttpResponse> strategy =
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE);
        final HttpClient client = retryingClientWith(strategy);

        final Stopwatch sw = Stopwatch.createStarted();

        final AggregatedHttpMessage res = client.get("/retry-after").aggregate().get();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(3000);
    }

    private HttpClient retryingClientWith(RetryStrategy<HttpRequest, HttpResponse> strategy) {
        return new ClientBuilder(server.uri(SerializationFormat.NONE, "/"))
                .decorator(HttpRequest.class, HttpResponse.class,
                           new RetryingHttpClientBuilder(strategy)
                                   .backoffSupplier(() -> Backoff.withoutDelay().withMaxAttempts(5))
                                   .useRetryAfter(true).newDecorator())
                .build(HttpClient.class);
    }

    @Test
    public void respectRetryAfterWithHttpDate() throws Exception {
        final RetryStrategy<HttpRequest, HttpResponse> strategy =
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE);
        final HttpClient client = retryingClientWith(strategy);

        final Stopwatch sw = Stopwatch.createStarted();

        final AggregatedHttpMessage res = client.get("/retry-after-with-http-date").aggregate().get();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");

        // Since ZonedDateTime doesn't express exact time,
        // just check out whether it is retried after delayed some time.
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(1000);
    }

    @Test
    public void retryAfterOneYear() throws Exception {
        final RetryStrategy<HttpRequest, HttpResponse> strategy =
                RetryStrategy.onStatus(HttpStatus.SERVICE_UNAVAILABLE);

        final HttpClient client = new ClientBuilder(server.uri(SerializationFormat.NONE, "/"))
                .factory(new ClientFactoryBuilder().idleTimeout(Duration.ofSeconds(5))
                                                   .build())
                .defaultResponseTimeout(Duration.ofSeconds(5))
                .decorator(HttpRequest.class, HttpResponse.class,
                           new RetryingHttpClientBuilder(strategy)
                                   .backoffSupplier(() -> Backoff.withoutDelay().withMaxAttempts(5))
                                   .useRetryAfter(true).newDecorator())
                .build(HttpClient.class);

        final Stopwatch sw = Stopwatch.createStarted();

        final AggregatedHttpMessage res = client.get("/retry-after-one-year").aggregate().get();
        assertThat(res.content().toStringUtf8()).isEqualTo("Succeeded after retry");

        // Retry after is limited by response time out which is 5 seconds in this case.
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isLessThan(8000);
    }
}

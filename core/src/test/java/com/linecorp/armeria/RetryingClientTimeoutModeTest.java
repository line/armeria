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

package com.linecorp.armeria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.ResponseTimeoutMode;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RetryingClientTimeoutModeTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.delayed(HttpResponse.of("OK"),
                                                            Duration.ofMillis(100)));
            sb.service("/slow", (ctx, req) -> HttpResponse.delayed(HttpResponse.of("OK"),
                                                                Duration.ofMillis(1500)));
            sb.service("/retry", (ctx, req) -> {
                final HttpStatus status = req.headers().contains(RetryingClient.ARMERIA_RETRY_COUNT) ?
                                          HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
                return HttpResponse.delayed(HttpResponse.of(status), Duration.ofMillis(100));
            });
            sb.service("/retry-timeout", (ctx, req) -> {
                final long delayMillis =
                        req.headers().contains(RetryingClient.ARMERIA_RETRY_COUNT) ? 100 : 1500;
                return HttpResponse.delayed(HttpResponse.of("OK"), Duration.ofMillis(delayMillis));
            });
            sb.service("/remaining-budget", (ctx, req) -> {
                final boolean retry = req.headers().contains(RetryingClient.ARMERIA_RETRY_COUNT);
                final HttpStatus status = retry ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
                return HttpResponse.delayed(HttpResponse.of(status),
                                            Duration.ofMillis(retry ? 1500 : 700));
            });
            sb.service("/echo", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(aggregated -> HttpResponse.of(aggregated.contentUtf8()))));
        }
    };

    @ParameterizedTest
    @CsvSource({
            "FROM_START, 0",
            "FROM_START, 1000",
            "FROM_START, 5000",
            "CONNECTION_ACQUIRED, 0",
            "CONNECTION_ACQUIRED, 1000",
            "CONNECTION_ACQUIRED, 5000",
            "REQUEST_SENT, 0",
            "REQUEST_SENT, 1000",
            "REQUEST_SENT, 5000"
    })
    void perAttemptTimeoutStartsAtConfiguredPhase(ResponseTimeoutMode timeoutMode, long responseTimeoutMillis) {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.responseTimeoutMillis(responseTimeoutMillis);
            cb.responseTimeoutMode(timeoutMode);
            cb.decorator(RetryingClientTimeoutModeTest::delayRequest);
            cb.decorator(RetryingClient.builder(RetryRule.failsafe())
                                       .maxTotalAttempts(1)
                                       .responseTimeoutMillisForEachAttempt(1000)
                                       .newDecorator());
        });

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            if (timeoutMode == ResponseTimeoutMode.FROM_START) {
                assertThatThrownBy(() -> client.get("/"))
                        .isInstanceOf(ResponseTimeoutException.class);
            } else {
                final AggregatedHttpResponse response = client.get("/");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(response.contentUtf8()).isEqualTo("OK");
            }
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(responseTimeoutMillis);
            assertThat(ctx.log().children()).hasSize(1);
        }
    }

    @ParameterizedTest
    @EnumSource(ResponseTimeoutMode.class)
    void perAttemptTimeoutStillExpires(ResponseTimeoutMode timeoutMode) {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.responseTimeoutMillis(5000);
            cb.responseTimeoutMode(timeoutMode);
            cb.decorator(RetryingClient.builder(RetryRule.failsafe())
                                       .maxTotalAttempts(1)
                                       .responseTimeoutMillisForEachAttempt(1000)
                                       .newDecorator());
        });

        assertThatThrownBy(() -> client.get("/slow"))
                .isInstanceOf(ResponseTimeoutException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ResponseTimeoutMode.class, names = "FROM_START", mode = EnumSource.Mode.EXCLUDE)
    void eachAttemptHasItsOwnTimeoutAndRunsInnerDecorators(ResponseTimeoutMode timeoutMode) {
        final AtomicInteger innerCalls = new AtomicInteger();
        final AtomicInteger outerCalls = new AtomicInteger();
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.responseTimeoutMillis(10000);
            cb.responseTimeoutMode(timeoutMode);
            cb.decorator((delegate, ctx, req) -> {
                innerCalls.incrementAndGet();
                return delayRequest(delegate, ctx, req);
            });
            cb.decorator(RetryingClient.builder(RetryRule.onServerErrorStatus(Backoff.withoutDelay()))
                                       .maxTotalAttempts(2)
                                       .responseTimeoutMillisForEachAttempt(1000)
                                       .newDecorator());
            cb.decorator((delegate, ctx, req) -> {
                outerCalls.incrementAndGet();
                return delegate.execute(ctx, req);
            });
        });

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.get("/retry").status()).isEqualTo(HttpStatus.OK);
            assertThat(outerCalls).hasValue(1);
            assertThat(innerCalls).hasValue(2);
            final ClientRequestContext ctx = captor.get();
            final RequestLog log = ctx.log().whenComplete().join();
            assertThat(log.children()).hasSize(2);
            assertThat(log.children()).allSatisfy(child -> assertThat(
                    ((ClientRequestContext) child.context()).responseTimeoutMillis()).isEqualTo(1000));
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(10000);
        }
    }

    @ParameterizedTest
    @EnumSource(ResponseTimeoutMode.class)
    void perAttemptTimeoutCanBeRetried(ResponseTimeoutMode timeoutMode) {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.responseTimeoutMillis(5000);
            cb.responseTimeoutMode(timeoutMode);
            cb.decorator(RetryingClient.builder(RetryRule.onTimeoutException(Backoff.withoutDelay()))
                                       .maxTotalAttempts(2)
                                       .responseTimeoutMillisForEachAttempt(1000)
                                       .newDecorator());
        });

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.get("/retry-timeout").contentUtf8()).isEqualTo("OK");
            final RequestLog log = captor.get().log().whenComplete().join();
            assertThat(log.children()).hasSize(2);
            assertThat(log.children().get(0).partial().responseCause())
                    .isInstanceOf(ResponseTimeoutException.class);
            assertThat(log.children().get(1).partial().responseCause()).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(ResponseTimeoutMode.class)
    void remainingSessionBudgetLimitsNextAttempt(ResponseTimeoutMode timeoutMode) {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.responseTimeoutMillis(1200);
            cb.responseTimeoutMode(timeoutMode);
            cb.decorator(RetryingClient.builder(RetryRule.onServerErrorStatus(Backoff.withoutDelay()))
                                       .maxTotalAttempts(2)
                                       .responseTimeoutMillisForEachAttempt(2000)
                                       .newDecorator());
        });

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> client.get("/remaining-budget"))
                    .isInstanceOf(ResponseTimeoutException.class);
            final ClientRequestContext ctx = captor.get();
            final RequestLog log = ctx.log().whenComplete().join();
            assertThat(log.children()).hasSize(2);
            final ClientRequestContext first = (ClientRequestContext) log.children().get(0).context();
            final ClientRequestContext second = (ClientRequestContext) log.children().get(1).context();
            assertThat(first.responseTimeoutMillis()).isPositive().isLessThanOrEqualTo(1200);
            assertThat(second.responseTimeoutMillis()).isPositive().isLessThan(first.responseTimeoutMillis());
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(1200);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "FROM_START, 0",
            "FROM_START, 1000",
            "CONNECTION_ACQUIRED, 0",
            "CONNECTION_ACQUIRED, 1000",
            "REQUEST_SENT, 0",
            "REQUEST_SENT, 1000"
    })
    void disabledPerAttemptTimeoutStillRespectsSessionBudget(ResponseTimeoutMode timeoutMode,
                                                            long responseTimeoutMillis) {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.responseTimeoutMillis(responseTimeoutMillis);
            cb.responseTimeoutMode(timeoutMode);
            cb.decorator(RetryingClient.builder(RetryRule.failsafe())
                                       .maxTotalAttempts(1)
                                       .responseTimeoutMillisForEachAttempt(0)
                                       .newDecorator());
        });

        if (responseTimeoutMillis == 0) {
            assertThat(client.get("/slow").contentUtf8()).isEqualTo("OK");
        } else {
            assertThatThrownBy(() -> client.get("/slow"))
                    .isInstanceOf(ResponseTimeoutException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(ResponseTimeoutMode.class)
    void explicitSetFromNowOverridesConfiguredPhase(ResponseTimeoutMode timeoutMode) {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.responseTimeoutMillis(5000);
            cb.responseTimeoutMode(timeoutMode);
            cb.decorator((delegate, ctx, req) -> {
                ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, 1000);
                return delayRequest(delegate, ctx, req);
            });
            cb.decorator(RetryingClient.builder(RetryRule.failsafe())
                                       .maxTotalAttempts(1)
                                       .responseTimeoutMillisForEachAttempt(3000)
                                       .newDecorator());
        });

        assertThatThrownBy(() -> client.get("/"))
                .isInstanceOf(ResponseTimeoutException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "FROM_START, H1C",
            "FROM_START, H2C",
            "CONNECTION_ACQUIRED, H1C",
            "CONNECTION_ACQUIRED, H2C",
            "REQUEST_SENT, H1C",
            "REQUEST_SENT, H2C"
    })
    void requestSentTimeoutExcludesRequestWriteTime(ResponseTimeoutMode timeoutMode, SessionProtocol protocol) {
        final HttpRequestWriter request = HttpRequest.streaming(HttpMethod.POST, "/echo");
        final WebClientBuilder builder = WebClient.builder(server.uri(protocol))
                                                  .responseTimeoutMillis(5000)
                                                  .responseTimeoutMode(timeoutMode)
                                                  .writeTimeoutMillis(0);
        builder.decorator((delegate, ctx, req) -> {
            ctx.log().whenAvailable(RequestLogProperty.SESSION).thenAccept(log -> {
                final ScheduledFuture<?> scheduled = ctx.eventLoop().schedule(() -> {
                    request.write(HttpData.ofUtf8("body"));
                    request.close();
                }, 1500, TimeUnit.MILLISECONDS);
                ctx.whenResponseCancelled().thenAccept(cause -> scheduled.cancel(false));
            });
            return delegate.execute(ctx, req);
        });
        builder.decorator(RetryingClient.builder(RetryRule.failsafe())
                                       .maxTotalAttempts(1)
                                       .responseTimeoutMillisForEachAttempt(1000)
                                       .newDecorator());
        final WebClient client = builder.build();
        try {
            final HttpResponse response = client.execute(
                    request, RequestOptions.builder().exchangeType(ExchangeType.BIDI_STREAMING).build());
            final CompletableFuture<AggregatedHttpResponse> aggregated = response.aggregate();
            if (timeoutMode == ResponseTimeoutMode.REQUEST_SENT) {
                assertThat(aggregated.join().contentUtf8()).isEqualTo("body");
            } else {
                assertThatThrownBy(aggregated::join)
                        .isInstanceOf(CompletionException.class)
                        .hasRootCauseInstanceOf(ResponseTimeoutException.class);
            }
        } finally {
            request.abort();
        }
    }

    private static HttpResponse delayRequest(HttpClient delegate, ClientRequestContext ctx, HttpRequest req) {
        final CompletableFuture<HttpResponse> response = new CompletableFuture<>();
        final ScheduledFuture<?> scheduled = ctx.eventLoop().schedule(
                () -> response.complete(delegate.execute(ctx, req)), 1500, TimeUnit.MILLISECONDS);
        ctx.whenResponseCancelled().thenAccept(cause -> {
            scheduled.cancel(false);
            response.completeExceptionally(cause);
        });
        return HttpResponse.of(response);
    }
}

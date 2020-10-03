/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestCancellationException;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpClientResponseTimeoutTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(0);
            sb.service("/no-timeout", (ctx, req) -> HttpResponse.streaming());
        }
    };

    @Test
    void shouldSetResponseTimeoutWithNoTimeout() {
        final WebClient client = WebClient
                .builder(server.httpUri())
                .option(ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(0L))
                .decorator((delegate, ctx, req) -> {
                    ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_START, 1000);
                    assertThat(ctx.responseTimeoutMillis()).isEqualTo(1000);
                    return delegate.execute(ctx, req);
                })
                .build();
        await().timeout(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/no-timeout")
                                           .aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ResponseTimeoutException.class);
        });
    }

    @ParameterizedTest
    @ArgumentsSource(TimeoutDecoratorSource.class)
    void setRequestTimeoutAtPendingTimeoutTask(Consumer<? super ClientRequestContext> timeoutCustomizer) {
        final WebClient client = WebClient
                .builder(server.httpUri())
                .option(ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(30L))
                .decorator((delegate, ctx, req) -> {
                    // set timeout before initializing timeout controller
                    timeoutCustomizer.accept(ctx);
                    return delegate.execute(ctx, req);
                })
                .build();
        await().timeout(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/no-timeout")
                                           .aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ResponseTimeoutException.class);
        });
    }

    @Test
    void whenTimedOut() {
        final AtomicReference<CompletableFuture<Void>> timeoutFutureRef = new AtomicReference<>();
        final WebClient client = WebClient
                .builder(server.httpUri())
                .option(ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(1000L))
                .decorator((delegate, ctx, req) -> {
                    timeoutFutureRef.set(ctx.whenResponseTimedOut());
                    return delegate.execute(ctx, req);
                })
                .build();

        await().timeout(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/no-timeout").aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ResponseTimeoutException.class);
        });

        await().untilAsserted(() -> {
            final CompletableFuture<Void> timeoutFuture = timeoutFutureRef.get();
            assertThat(timeoutFuture).isInstanceOf(UnmodifiableFuture.class);
            assertThat(timeoutFuture).isDone();
        });
    }

    @Test
    void timeoutWithContext() {
        final WebClient client = WebClient
                .builder(server.httpUri())
                .build();
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final CompletableFuture<AggregatedHttpResponse> response = client.get("/no-timeout").aggregate();
            final ClientRequestContext cctx = ctxCaptor.get();
            cctx.timeoutNow();
            assertThat(cctx.isTimedOut()).isFalse();
            assertThatThrownBy(response::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ResponseTimeoutException.class);
            assertThat(cctx.isTimedOut()).isTrue();
        }
    }

    @Test
    void cancel() {
        final WebClient client = WebClient
                .builder(server.httpUri())
                .decorator((delegate, ctx, req) -> {
                    ctx.cancel();
                    return delegate.execute(ctx, req);
                })
                .build();
        assertThatThrownBy(() -> client.get("/no-timeout").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RequestCancellationException.class);
    }

    @Test
    void cancelWithContext() {
        final WebClient client = WebClient
                .builder(server.httpUri())
                .build();
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final CompletableFuture<AggregatedHttpResponse> response = client.get("/no-timeout").aggregate();
            final ClientRequestContext cctx = ctxCaptor.get();
            cctx.cancel();
            assertThat(cctx.isCancelled()).isFalse();
            assertThatThrownBy(response::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RequestCancellationException.class);
            assertThat(cctx.isCancelled()).isTrue();
        }
    }

    private static class TimeoutDecoratorSource implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext)
                throws Exception {
            final Stream<Consumer<? super ClientRequestContext>> timeoutCustomizers = Stream.of(
                    ctx -> ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, 1000),
                    ctx -> ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_START, 1000),
                    RequestContext::timeoutNow
            );
            return timeoutCustomizers.map(Arguments::of);
        }
    }
}

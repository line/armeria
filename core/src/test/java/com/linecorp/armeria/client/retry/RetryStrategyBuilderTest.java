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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WriteTimeoutException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.ClosedStreamException;

class RetryStrategyBuilderTest {

    static <T> ObjectAssert<T> assertFutureValue(CompletionStage<T> future) {
        return assertThat(((CompletableFuture<T>) future).join());
    }

    @Test
    void onStatusClass() {
        final RetryStrategy strategy = RetryStrategy.builder()
                                                    .onStatusClass(HttpStatusClass.CLIENT_ERROR)
                                                    .build();

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.BAD_REQUEST));
        assertFutureValue(strategy.shouldRetry(ctx1, null)).isSameAs(Backoff.ofDefault());

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        assertFutureValue(strategy.shouldRetry(ctx2, null)).isNull();
    }

    @Test
    void onStatusErrorStatus() {
        final Backoff backoff = Backoff.fixed(2000);
        final RetryStrategy strategy = RetryStrategy.builder().onServerErrorStatus(backoff).build();

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertFutureValue(strategy.shouldRetry(ctx1, null)).isSameAs(backoff);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.BAD_REQUEST));
        assertFutureValue(strategy.shouldRetry(ctx2, null)).isNull();
    }

    @Test
    void onStatus() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        final Backoff backoff = Backoff.fixed(1000);
        final RetryStrategy strategy = RetryStrategy.builder()
                                                    .onStatus(HttpStatus.INTERNAL_SERVER_ERROR, backoff)
                                                    .build();

        assertFutureValue(strategy.shouldRetry(ctx, null)).isSameAs(backoff);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.GATEWAY_TIMEOUT));
        assertFutureValue(strategy.shouldRetry(ctx2, null)).isNull();
    }

    @Test
    void onException() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Backoff backoff1 = Backoff.fixed(1000);
        final Backoff backoff2 = Backoff.fixed(2000);
        final RetryStrategy strategy =
                RetryStrategy.builder()
                             .onException(ClosedSessionException.class, backoff1)
                             .onException(WriteTimeoutException.class::isInstance, backoff2)
                             .build();

        assertFutureValue(strategy.shouldRetry(ctx, ClosedSessionException.get())).isSameAs(backoff1);
        assertFutureValue(strategy.shouldRetry(ctx, WriteTimeoutException.get())).isSameAs(backoff2);
        assertFutureValue(strategy.shouldRetry(ctx, ResponseTimeoutException.get())).isNull();
        assertFutureValue(strategy.shouldRetry(ctx, null)).isNull();
    }

    @Test
    void onAnyException() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Backoff backoff1 = Backoff.fixed(1000);
        final Backoff backoff2 = Backoff.fixed(2000);
        final RetryStrategy strategy = RetryStrategy.builder()
                                                    .onException(backoff1)
                                                    .onException(ClosedSessionException.class, backoff2)
                                                    .build();

        assertFutureValue(strategy.shouldRetry(ctx, ClosedSessionException.get())).isSameAs(backoff1);
        assertFutureValue(strategy.shouldRetry(ctx, new RuntimeException())).isNotNull();
        assertFutureValue(strategy.shouldRetry(ctx, null)).isNull();
    }

    @Test
    void multipleStrategy() {
        final Backoff statusErrorBackOff = Backoff.fixed(2000);
        final Backoff unProcessBackOff = Backoff.exponential(4000, 40000);
        final Backoff statusBackOff = Backoff.fibonacci(5000, 10000);
        final Backoff clientErrorBackOff = Backoff.fixed(3000);
        final RetryStrategy strategy =
                RetryStrategy.builder()
                             .onUnProcessed(unProcessBackOff)
                             .onException()
                             .onServerErrorStatus(statusErrorBackOff)
                             .onStatus(HttpStatus.TOO_MANY_REQUESTS, statusBackOff)
                             .addRetryStrategy((ctx, cause) -> {
                                 if (ctx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                                     final HttpStatus responseStatus =
                                             ctx.log().partial().responseHeaders().status();
                                     if (responseStatus.isClientError()) {
                                         return CompletableFuture.completedFuture(clientErrorBackOff);
                                     }
                                 }
                                 return CompletableFuture.completedFuture(null);
                             })
                             .build();

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertFutureValue(strategy.shouldRetry(ctx1, null)).isSameAs(statusErrorBackOff);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.TOO_MANY_REQUESTS));
        assertFutureValue(strategy.shouldRetry(ctx2, null)).isSameAs(statusBackOff);

        final ClientRequestContext ctx3 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final CompletionException ex =
                new CompletionException(new UnprocessedRequestException(ClosedStreamException.get()));
        assertFutureValue(strategy.shouldRetry(ctx3, ex))
                .isSameAs(unProcessBackOff);

        final ClientRequestContext ctx4 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertFutureValue(strategy.shouldRetry(ctx4, new RuntimeException())).isSameAs(Backoff.ofDefault());

        final ClientRequestContext ctx5 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx5.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.CONFLICT));
        assertFutureValue(strategy.shouldRetry(ctx5, null)).isSameAs(clientErrorBackOff);
    }
}

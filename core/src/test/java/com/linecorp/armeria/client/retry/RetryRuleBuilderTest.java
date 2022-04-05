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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WriteTimeoutException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

class RetryRuleBuilderTest {

    private static final Backoff statusErrorBackOff = Backoff.fixed(2000);
    private static final Backoff unprocessBackOff = Backoff.exponential(4000, 40000);
    private static final Backoff statusBackOff = Backoff.fibonacci(5000, 10000);
    private static final Backoff clientErrorBackOff = Backoff.fixed(3000);

    static ObjectAssert<Backoff> assertBackoff(CompletionStage<RetryDecision> future) {
        return assertThat(future.toCompletableFuture().join().backoff());
    }

    @Test
    void onStatusClass() {
        final RetryRule rule = RetryRule.onStatusClass(HttpStatusClass.CLIENT_ERROR);

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.BAD_REQUEST));
        assertBackoff(rule.shouldRetry(ctx1, null)).isSameAs(Backoff.ofDefault());

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        assertBackoff(rule.shouldRetry(ctx2, null)).isNull();
    }

    @Test
    void onStatusErrorStatus() {
        final Backoff backoff = Backoff.fixed(2000);
        final RetryRule rule = RetryRule.builder().onServerErrorStatus().thenBackoff(backoff);

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertBackoff(rule.shouldRetry(ctx1, null)).isSameAs(backoff);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.BAD_REQUEST));
        assertBackoff(rule.shouldRetry(ctx2, null)).isNull();
    }

    @Test
    void onStatus() {
        final Backoff backoff500 = Backoff.fixed(1000);
        final Backoff backoff502 = Backoff.fixed(1000);
        final RetryRule rule =
                RetryRule.builder()
                         .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                         .thenBackoff(backoff500)
                         .orElse(RetryRule.builder()
                                          .onStatus((unused, status) -> HttpStatus.BAD_GATEWAY.equals(status))
                                          .thenBackoff(backoff502));

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertBackoff(rule.shouldRetry(ctx1, null)).isSameAs(backoff500);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.BAD_GATEWAY));
        assertBackoff(rule.shouldRetry(ctx2, null)).isSameAs(backoff502);

        final ClientRequestContext ctx3 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx3.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.GATEWAY_TIMEOUT));
        assertBackoff(rule.shouldRetry(ctx3, null)).isNull();
    }

    @Test
    void onResponseTrailers() {
        final Backoff backoff = Backoff.fixed(1000);
        final RetryRule rule =
                RetryRule.builder()
                         .onResponseTrailers((unused, trailers) -> trailers.containsInt("grpc-status", 3))
                         .thenBackoff(backoff);

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseTrailers(HttpHeaders.of("grpc-status", 3));
        assertBackoff(rule.shouldRetry(ctx1, null)).isSameAs(backoff);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseTrailers(HttpHeaders.of("grpc-status", 0));
        assertBackoff(rule.shouldRetry(ctx2, null)).isNull();
    }

    @Test
    void onException() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Backoff backoff1 = Backoff.fixed(1000);
        final Backoff backoff2 = Backoff.fixed(2000);
        final RetryRule rule = RetryRule.of(RetryRule.builder()
                                                     .onException(ClosedSessionException.class)
                                                     .thenBackoff(backoff1),
                                            RetryRule.builder()
                                                     .onException((unused, obj) -> {
                                                         return "/".equals(ctx.path()) &&
                                                                obj instanceof WriteTimeoutException;
                                                     })
                                                     .thenBackoff(backoff2));

        assertBackoff(rule.shouldRetry(ctx, ClosedSessionException.get())).isSameAs(backoff1);
        assertBackoff(rule.shouldRetry(ctx, new CompletionException(ClosedSessionException.get())))
                .isSameAs(backoff1);
        assertBackoff(rule.shouldRetry(ctx, WriteTimeoutException.get())).isSameAs(backoff2);
        assertBackoff(rule.shouldRetry(ctx, ResponseTimeoutException.get())).isNull();
        assertBackoff(rule.shouldRetry(ctx, null)).isNull();
    }

    @Test
    void onAnyException() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Backoff backoff1 = Backoff.fixed(1000);
        final Backoff backoff2 = Backoff.fixed(2000);

        final RetryRule rule = RetryRule.builder().onException().thenBackoff(backoff1)
                                        .orElse(RetryRule.builder().onException(ClosedSessionException.class)
                                                         .thenBackoff(backoff2));

        assertBackoff(rule.shouldRetry(ctx, ClosedSessionException.get())).isSameAs(backoff1);
        assertBackoff(rule.shouldRetry(ctx, new RuntimeException())).isNotNull();
        assertBackoff(rule.shouldRetry(ctx, null)).isNull();
    }

    @Test
    void multipleRule() {
        final RetryRule retryRule =
                RetryRule.builder().onUnprocessed().thenBackoff(unprocessBackOff)
                         .orElse(RetryRule.onException())
                         .orElse((ctx, cause) -> {
                             if (ctx.log().isAvailable(
                                     RequestLogProperty.RESPONSE_HEADERS)) {
                                 final HttpStatus status = ctx.log().partial().responseHeaders().status();
                                 if (status.isClientError()) {
                                     return UnmodifiableFuture.completedFuture(
                                             RetryDecision.retry(clientErrorBackOff));
                                 }
                             }
                             return UnmodifiableFuture.completedFuture(RetryDecision.next());
                         })
                         .orElse(RetryRule.builder().onServerErrorStatus().thenBackoff(statusErrorBackOff))
                         .orElse(RetryRule.builder().onStatus(HttpStatus.TOO_MANY_REQUESTS)
                                          .thenBackoff(statusBackOff));

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertBackoff(retryRule.shouldRetry(ctx1, null)).isSameAs(statusErrorBackOff);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.TOO_MANY_REQUESTS));
        assertBackoff(retryRule.shouldRetry(ctx2, null)).isSameAs(clientErrorBackOff);

        final ClientRequestContext ctx3 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final CompletionException ex =
                new CompletionException(UnprocessedRequestException.of(ClosedStreamException.get()));
        assertBackoff(retryRule.shouldRetry(ctx3, ex)).isSameAs(unprocessBackOff);

        final ClientRequestContext ctx4 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertBackoff(retryRule.shouldRetry(ctx4, new RuntimeException())).isSameAs(Backoff.ofDefault());

        final ClientRequestContext ctx5 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx5.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.CONFLICT));
        assertBackoff(retryRule.shouldRetry(ctx5, null)).isSameAs(clientErrorBackOff);
    }

    @Test
    void customRule() {
        final Backoff backoff = Backoff.fixed(1000);
        final RetryRule retryRule = RetryRule.of((ctx, cause) -> {
            if (cause instanceof UnprocessedRequestException) {
                // retry with backoff
                return UnmodifiableFuture.completedFuture(RetryDecision.retry(backoff));
            }
            if (ctx.log().partial().responseHeaders().status().isClientError()) {
                // stop retrying
                return UnmodifiableFuture.completedFuture(RetryDecision.noRetry());
            }
            // will lookup next strategies
            return UnmodifiableFuture.completedFuture(RetryDecision.next());
        }, RetryRule.builder()
                    .onStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .thenBackoff(backoff));

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final UnprocessedRequestException cause =
                UnprocessedRequestException.of(ResponseTimeoutException.get());
        assertBackoff(retryRule.shouldRetry(ctx1, cause)).isSameAs(backoff);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.UNAUTHORIZED));
        assertBackoff(retryRule.shouldRetry(ctx2, null)).isNull();

        final ClientRequestContext ctx3 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx3.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.SERVICE_UNAVAILABLE));
        assertBackoff(retryRule.shouldRetry(ctx3, null)).isSameAs(backoff);

        final ClientRequestContext ctx4 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx4.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        assertBackoff(retryRule.shouldRetry(ctx4, null)).isNull();
    }

    @Test
    void methodFilter() {
        assertThatThrownBy(() -> RetryRule.builder(HttpMethod.HEAD).thenBackoff())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least one");

        final RetryRule rule = RetryRule.builder(HttpMethod.HEAD)
                                        .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .thenBackoff();

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.HEAD, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertBackoff(rule.shouldRetry(ctx1, null)).isSameAs(Backoff.ofDefault());

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertBackoff(rule.shouldRetry(ctx2, null)).isNull();
    }

    @Test
    void buildFluently() {
        final Backoff idempotentBackoff = Backoff.fixed(100);
        final Backoff unprocessedBackoff = Backoff.fixed(200);
        final RetryRule retryRule =
                RetryRule.of(RetryRule.builder(HttpMethod.idempotentMethods())
                                      .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                      .onException(ClosedChannelException.class)
                                      .onStatusClass(HttpStatusClass.CLIENT_ERROR)
                                      .thenBackoff(idempotentBackoff),
                             RetryRule.builder()
                                      .onUnprocessed()
                                      .thenBackoff(unprocessedBackoff));

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));

        assertBackoff(retryRule.shouldRetry(ctx1, null)).isSameAs(idempotentBackoff);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertBackoff(retryRule.shouldRetry(ctx2, null)).isNull();

        final ClientRequestContext ctx3 = ClientRequestContext.of(HttpRequest.of(HttpMethod.PUT, "/"));
        assertBackoff(retryRule.shouldRetry(ctx3, new ClosedChannelException())).isSameAs(idempotentBackoff);

        final ClientRequestContext ctx4 = ClientRequestContext.of(HttpRequest.of(HttpMethod.PUT, "/"));
        assertBackoff(retryRule.shouldRetry(ctx4,
                                            UnprocessedRequestException.of(new ClosedChannelException())))
                .isSameAs(unprocessedBackoff);
    }

    @Test
    void noRetry() {
        final RetryRule rule = RetryRule.builder(HttpMethod.POST).thenNoRetry()
                                        .orElse(RetryRule.onException());

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertBackoff(rule.shouldRetry(ctx1, new RuntimeException())).isSameAs(Backoff.ofDefault());

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"));
        assertBackoff(rule.shouldRetry(ctx2, new RuntimeException())).isNull();
    }

    @Test
    void noDelay() {
        final int maxAttempts = 10;
        final RetryRule rule = RetryRule.builder()
                                        .onStatus((unused, status) -> status == HttpStatus.BAD_REQUEST ||
                                                                      status == HttpStatus.TOO_MANY_REQUESTS)
                                        .thenBackoff(Backoff.withoutDelay().withMaxAttempts(maxAttempts));

        for (int i = 1; i < maxAttempts; i++) {
            final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.BAD_REQUEST));
            assertThat(rule.shouldRetry(ctx1, null).toCompletableFuture().join()
                           .backoff().nextDelayMillis(i))
                    .isEqualTo(0);

            final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
            assertBackoff(rule.shouldRetry(ctx2, null)).isNull();
        }

        final ClientRequestContext ctx3 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx3.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.BAD_REQUEST));
        assertThat(rule.shouldRetry(ctx3, null).toCompletableFuture().join()
                       .backoff().nextDelayMillis(maxAttempts + 1))
                .isEqualTo(-1);
    }
}

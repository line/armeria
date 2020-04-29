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

    private static final Backoff statusErrorBackOff = Backoff.fixed(2000);
    private static final Backoff unprocessBackOff = Backoff.exponential(4000, 40000);
    private static final Backoff statusBackOff = Backoff.fibonacci(5000, 10000);
    private static final Backoff clientErrorBackOff = Backoff.fixed(3000);

    static ObjectAssert<Backoff> assertBackoff(CompletionStage<RetryRuleDecision> future) {
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
                         .or(RetryRule.builder()
                                      .onStatus(HttpStatus.BAD_GATEWAY::equals)
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
    void onException() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Backoff backoff1 = Backoff.fixed(1000);
        final Backoff backoff2 = Backoff.fixed(2000);
        final RetryRule rule = RetryRule.builder()
                                        .onException(ClosedSessionException.class)
                                        .thenBackoff(backoff1)
                                        .or(RetryRule.builder()
                                                     .onException(WriteTimeoutException.class::isInstance)
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
                                        .or(RetryRule.builder().onException(ClosedSessionException.class)
                                                     .thenBackoff(backoff2));

        assertBackoff(rule.shouldRetry(ctx, ClosedSessionException.get())).isSameAs(backoff1);
        assertBackoff(rule.shouldRetry(ctx, new RuntimeException())).isNotNull();
        assertBackoff(rule.shouldRetry(ctx, null)).isNull();
    }

    @Test
    void multipleRule() {
        final RetryRule retryRule =
                RetryRule.builder().onUnprocessed().thenBackoff(unprocessBackOff)
                         .or(RetryRule.onException())
                         .or((ctx, cause) -> {
                             if (ctx.log().isAvailable(
                                     RequestLogProperty.RESPONSE_HEADERS)) {
                                 final HttpStatus status = ctx.log().partial().responseHeaders().status();
                                 if (status.isClientError()) {
                                     return CompletableFuture.completedFuture(
                                             RetryRuleDecision.retry(clientErrorBackOff));
                                 }
                             }
                             return CompletableFuture.completedFuture(RetryRuleDecision.next());
                         })
                         .or(RetryRule.builder().onServerErrorStatus().thenBackoff(statusErrorBackOff))
                         .or(RetryRule.builder().onStatus(HttpStatus.TOO_MANY_REQUESTS)
                                      .thenBackoff(statusBackOff));

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertBackoff(retryRule.shouldRetry(ctx1, null)).isSameAs(statusErrorBackOff);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.TOO_MANY_REQUESTS));
        assertBackoff(retryRule.shouldRetry(ctx2, null)).isSameAs(clientErrorBackOff);

        final ClientRequestContext ctx3 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final CompletionException ex =
                new CompletionException(new UnprocessedRequestException(ClosedStreamException.get()));
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
        final RetryRule retryRule = ((RetryRule) (ctx, cause) -> {
            if (cause instanceof UnprocessedRequestException) {
                // retry with backoff
                return CompletableFuture.completedFuture(RetryRuleDecision.retry(backoff));
            }
            if (ctx.log().partial().responseHeaders().status().isClientError()) {
                // stop retrying
                return CompletableFuture.completedFuture(RetryRuleDecision.noRetry());
            }
            // will lookup next strategies
            return CompletableFuture.completedFuture(RetryRuleDecision.next());
        }).or(RetryRule.builder().onStatus(HttpStatus.SERVICE_UNAVAILABLE).thenBackoff(backoff));

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final UnprocessedRequestException cause =
                new UnprocessedRequestException(ResponseTimeoutException.get());
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
}

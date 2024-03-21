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

package com.linecorp.armeria.client.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WriteTimeoutException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

class CircuitBreakerRuleBuilderTest {

    ClientRequestContext ctx1;
    ClientRequestContext ctx2;

    @BeforeEach
    void setUp() {
        ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }

    @Test
    void shouldAllowEmptyRule() {
        CircuitBreakerRule.builder().thenFailure();
        CircuitBreakerRule.builder().thenSuccess();
        CircuitBreakerRule.builder().thenIgnore();
    }

    @Test
    void shouldReportAsSuccess() {
        final CircuitBreakerRule rule = CircuitBreakerRule.builder()
                                                          .onStatus(HttpStatus.NOT_FOUND)
                                                          .thenSuccess();
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.NOT_FOUND));
        assertFuture(rule.shouldReportAsSuccess(ctx1, null)).isSameAs(CircuitBreakerDecision.success());

        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        assertFuture(rule.shouldReportAsSuccess(ctx2, null)).isSameAs(CircuitBreakerDecision.next());
    }

    @Test
    void shouldReportAsFailure() {
        final CircuitBreakerRule rule = CircuitBreakerRule
                .builder()
                .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .thenFailure()
                .orElse(CircuitBreakerRule.onTimeoutException());
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertFuture(rule.shouldReportAsSuccess(ctx1, null)).isSameAs(CircuitBreakerDecision.failure());
        assertFuture(rule.shouldReportAsSuccess(
                ctx2, new CompletionException(UnprocessedRequestException.of(WriteTimeoutException.get()))))
                .isSameAs(CircuitBreakerDecision.failure());
        final ClientRequestContext timedOutCtx = ClientRequestContext
                .builder(HttpRequest.of(HttpMethod.GET, "/"))
                .timedOut(true)
                .build();
        assertFuture(rule.shouldReportAsSuccess(timedOutCtx, ClosedSessionException.get()))
                .isSameAs(CircuitBreakerDecision.failure());

        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        assertFuture(rule.shouldReportAsSuccess(ctx2, null)).isSameAs(CircuitBreakerDecision.next());
    }

    @Test
    void combineRuleWithOrElse() {
        final CircuitBreakerRule rule =
                CircuitBreakerRule.builder()
                                  .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                  .thenFailure()
                                  .orElse(CircuitBreakerRule.builder()
                                                            .onStatus(HttpStatus.OK)
                                                            .thenSuccess());

        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        assertFuture(rule.shouldReportAsSuccess(ctx1, null)).isSameAs(CircuitBreakerDecision.success());
    }

    @Test
    void combineRuleWithOf() {
        final CircuitBreakerRule rule =
                CircuitBreakerRule.of(CircuitBreakerRule.builder()
                                                        .onException(ClosedSessionException.class)
                                                        .thenFailure(),
                                      CircuitBreakerRule.onTimeoutException(),
                                      CircuitBreakerRule.builder()
                                                        .onStatus(HttpStatus.OK)
                                                        .thenSuccess());

        assertFuture(rule.shouldReportAsSuccess(ctx2, ClosedSessionException.get()))
                .isSameAs(CircuitBreakerDecision.failure());
        assertFuture(rule.shouldReportAsSuccess(
                ctx2, new CompletionException(UnprocessedRequestException.of(WriteTimeoutException.get()))))
                .isSameAs(CircuitBreakerDecision.failure());
        final ClientRequestContext timedOutCtx = ClientRequestContext
                .builder(HttpRequest.of(HttpMethod.GET, "/"))
                .timedOut(true)
                .build();
        assertFuture(rule.shouldReportAsSuccess(timedOutCtx, ClosedSessionException.get()))
                .isSameAs(CircuitBreakerDecision.failure());
        assertFuture(rule.shouldReportAsSuccess(ctx2, null))
                .isSameAs(CircuitBreakerDecision.next());
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        assertFuture(rule.shouldReportAsSuccess(ctx2, null))
                .isSameAs(CircuitBreakerDecision.success());
    }

    static <T> ObjectAssert<T> assertFuture(CompletionStage<T> decisionFuture) {
        return assertThat(decisionFuture.toCompletableFuture().join());
    }
}

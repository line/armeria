/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

class RetryLimiterIntegrationTest {

    @Test
    void concurrencyLimiting() throws Exception {
        final Backoff fixed = Backoff.fixed(0);
        final RetryRule rule =
                RetryRule.builder()
                         .onStatus(HttpStatus.OK)
                         .build(RetryDecision.retry(fixed, 1));
        final int maxRequests = 3;
        final RetryConfig<HttpResponse> config =
                RetryConfig.builder(rule)
                           .retryLimiter(RetryLimiter.concurrencyLimiting(maxRequests))
                           .build();

        final AtomicInteger counter = new AtomicInteger();
        final ArrayDeque<HttpResponse> deque = new ArrayDeque<>();
        final WebClient client =
                WebClient.builder("http://foo.com")
                         .decorator((delegate, ctx, req) -> {
                             counter.incrementAndGet();
                             if (ctx.log().partial().currentAttempt() > 1) {
                                 return HttpResponse.streaming();
                             }
                             return HttpResponse.of(200);
                         })
                         .decorator(RetryingClient.newDecorator(config))
                         .build();
        for (int i = 0; i < maxRequests; i++) {
            deque.add(client.get("/"));
        }

        assertThatThrownBy(() -> client.blocking().get("/")).isInstanceOf(RetryLimitedException.class);
        while (!deque.isEmpty()) {
            deque.poll().abort();
        }
        assertThat(counter).hasValue(maxRequests * 2 + 1);
    }

    @Test
    void bucketLimiting() throws Exception {
        final Backoff fixed = Backoff.fixed(0);
        // emulates grpc retry throttling behavior
        final RetryRule retryRule = RetryRule.of(
                RetryRule.builder()
                         .onResponseHeaders((ctx, trailers) -> trailers.containsInt("grpc-status", 0))
                         .build(RetryDecision.noRetry(-1)),
                RetryRule.builder()
                         .onResponseHeaders((ctx, trailers) -> trailers.containsInt("grpc-status", 11))
                         .build(RetryDecision.retry(fixed, 1))
        );
        final RetryConfig<HttpResponse> config = RetryConfig.builder(retryRule)
                                                            .retryLimiter(RetryLimiter.tokenBased(3, 1))
                                                            .build();

        final AtomicInteger counter = new AtomicInteger();
        final BlockingWebClient client =
                WebClient.builder("http://foo.com")
                         .decorator((delegate, ctx, req) -> {
                             counter.incrementAndGet();
                             return HttpResponse.of(ResponseHeaders.builder(200)
                                                                   .add("grpc-status", "11")
                                                                   .build());
                         })
                         .decorator(RetryingClient.newDecorator(config))
                         .build()
                         .blocking();
        assertThatThrownBy(() -> client.get("/")).isInstanceOf(RetryLimitedException.class);
        assertThat(counter).hasValue(2);
    }

    @Test
    void throwingLimiter() throws Exception {
        final Backoff fixed = Backoff.fixed(0);
        final RetryRule rule =
                RetryRule.builder()
                         .onStatus(HttpStatus.OK)
                         .build(RetryDecision.retry(fixed, 1));
        final int maxRequests = 3;
        final RetryConfig<HttpResponse> config =
                RetryConfig.builder(rule)
                           .retryLimiter(new RetryLimiter() {
                               @Override
                               public boolean shouldRetry(ClientRequestContext ctx) {
                                   throw new RuntimeException();
                               }

                               @Override
                               public void handleDecision(ClientRequestContext ctx, RetryDecision decision) {
                                   throw new RuntimeException();
                               }
                           })
                           .maxTotalAttempts(maxRequests)
                           .build();

        final AtomicInteger counter = new AtomicInteger();
        final BlockingWebClient client =
                WebClient.builder("http://foo.com")
                         .decorator((delegate, ctx, req) -> {
                             counter.incrementAndGet();
                             return HttpResponse.of(200);
                         })
                         .decorator(RetryingClient.newDecorator(config))
                         .build().blocking();

        assertThatThrownBy(() -> client.get("/")).isInstanceOf(RetryLimitedException.class);
        assertThat(counter).hasValue(1);
    }
}

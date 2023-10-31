/*
 * Copyright 2023 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

class CircuitBreakerWithRetryTest {

    @Test
    void shouldPropagateChildResponseCauseToCircuitBreakerRule() {
        final RetryRule retryRule = RetryRule.builder()
                                             .onServerErrorStatus()
                                             .thenBackoff(Backoff.fixed(1));
        final RetryConfig<HttpResponse> config = RetryConfig.builder(retryRule)
                                                            .maxTotalAttempts(2)
                                                            .build();
        final CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaultName();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final CircuitBreakerRule rule = (ctx, cause) -> {
            causeRef.set(cause);
            return UnmodifiableFuture.completedFuture(CircuitBreakerDecision.failure());
        };
        final BlockingWebClient client =
                WebClient.builder("http://127.0.0.1:1")
                         .decorator(RetryingClient.newDecorator(config))
                         .decorator(CircuitBreakerClient.newDecorator(circuitBreaker, rule))
                         .build()
                         .blocking();

        assertThatThrownBy(() -> client.get("/")).isInstanceOf(UnprocessedRequestException.class)
                                                 .hasCauseInstanceOf(ConnectException.class);
        assertThat(causeRef.get()).isInstanceOf(UnprocessedRequestException.class)
                                  .hasCauseInstanceOf(ConnectException.class);
    }
}

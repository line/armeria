/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.resilience4j.circuitbreaker.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.Builder;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@GenerateNativeImageTrace
class Resilience4jCircuitBreakerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/500", (ctx, req) -> HttpResponse.of(500));
        }
    };

    @Test
    void testBasicClientIntegration() throws Exception {
        final int minimumNumberOfCalls = 3;
        final CircuitBreakerConfig config = new Builder()
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .build();
        final CircuitBreakerRule rule = CircuitBreakerRule.onStatusClass(HttpStatusClass.SERVER_ERROR);
        final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        final Resilience4jCircuitBreakerMapping mapping = Resilience4jCircuitBreakerMapping.builder()
                                                                                           .registry(registry)
                                                                                           .perHost()
                                                                                           .build();
        final Function<? super HttpClient, CircuitBreakerClient> circuitBreakerDecorator =
                CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandler.of(mapping),
                                                  rule);
        final BlockingWebClient client = server.blockingWebClient(b -> b.decorator(circuitBreakerDecorator));
        for (int i = 0; i < minimumNumberOfCalls; i++) {
            assertThat(client.get("/500").status().code()).isEqualTo(500);
        }

        // wait until the circuitbreaker is open
        assertThat(registry.getAllCircuitBreakers()).hasSize(1);
        final CircuitBreaker cb = registry.getAllCircuitBreakers().stream().findFirst().orElseThrow();
        await().untilAsserted(() -> assertThat(cb.getState()).isEqualTo(State.OPEN));

        assertThatThrownBy(() -> client.get("/500")).isInstanceOf(UnprocessedRequestException.class)
                                                    .hasCauseInstanceOf(CallNotPermittedException.class);
    }
}

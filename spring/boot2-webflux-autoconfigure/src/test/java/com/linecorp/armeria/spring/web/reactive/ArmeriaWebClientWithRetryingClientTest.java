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
package com.linecorp.armeria.spring.web.reactive;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ArmeriaWebClientWithRetryingClientTest {

    @Test
    void exceptionPropagated() {
        final WebClient client = WebClient.builder()
                                          .clientConnector(new ArmeriaClientHttpConnector(builder -> builder
                                                  .decorator((delegate, ctx, req) -> {
                                                      throw new AnticipatedException();
                                                  })
                                                  .decorator(RetryingClient.newDecorator(
                                                          RetryRule.builder().thenNoRetry()))))
                                          .build();
        final Flux<String> body =
                client.get()
                      .uri("http://127.0.0.1/hello")
                      .retrieve()
                      .bodyToFlux(String.class);
        StepVerifier.create(body)
                    .expectError(AnticipatedException.class)
                    .verify();
    }
}

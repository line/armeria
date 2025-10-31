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

import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RetryingClientContextLeakTest {

    @Order(0)
    @RegisterExtension
    static ServerExtension backend = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("backend"));
        }
    };

    @Order(1)
    @RegisterExtension
    static ServerExtension frontend = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final WebClient backendClient = backend.webClient(cb -> {
                cb.decorator(RetryingClient.newDecorator(RetryRule.failsafe()));
            });
            sb.annotatedService(new MyService(backendClient));
        }
    };

    @Test
    void shouldPassWithRetryingClient() {
        final BlockingWebClient client = frontend.blockingWebClient();
        final AggregatedHttpResponse response = client.get("/");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("backend");
    }

    @Test
    void shouldIgnoreNullRootContext() {
        final ClientRequestContext cctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(cctx.root()).isNull();
        final ServiceRequestContext sctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        try (SafeCloseable ignored0 = cctx.push()) {
            assertThat(ClientRequestContext.current()).isSameAs(cctx);
            try (SafeCloseable ignored1 = sctx.push()) {
                assertThat(ServiceRequestContext.current()).isSameAs(sctx);
                assertThat(ClientRequestContext.currentOrNull()).isNull();
            }
        }
    }

    private static class MyService {
        private final WebClient backendClient;

        MyService(WebClient backendClient) {
            this.backendClient = backendClient;
        }

        @Get("/")
        public CompletionStage<String> myMethod() {
            // Execute an in non-context aware thread to make ClientRequestContext.root() return null.
            return CompletableFutures.supplyAsyncCompose(() -> backendClient.get("/").aggregate())
                                     .thenApply(AggregatedHttpResponse::contentUtf8);
        }
    }
}

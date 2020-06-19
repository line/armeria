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

package com.linecorp.armeria.client.unsafe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.unsafe.PooledAggregatedHttpResponse;
import com.linecorp.armeria.common.unsafe.PooledHttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class SimplePooledDecoratingHttpClientTest {

    private static final class PooledDecorator extends SimplePooledDecoratingHttpClient {

        private PooledDecorator(HttpClient delegate) {
            super(delegate);
        }

        @Override
        protected HttpResponse execute(PooledHttpClient delegate, ClientRequestContext ctx,
                                     PooledHttpRequest req) throws Exception {
            // Whether the decorator is applied to an unpooled or pooled delegate, it doesn't matter, we have
            // easy access to the unsafe API.
            return HttpResponse.from(
                    delegate.execute(ctx, req).aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc())
                            .thenApply(agg -> {
                                try (SafeCloseable unused = agg) {
                                    return HttpResponse.of(agg.contentUtf8() + " and goodbye!");
                                }
                            }));
        }
    }

    @RegisterExtension
    public static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("Hi"));
        }
    };

    private WebClient unpooledClient;
    private PooledWebClient pooledClient;

    @BeforeEach
    void setUp() {
        unpooledClient = WebClient.builder(server.httpUri())
                                  .decorator(PooledDecorator::new)
                                  .build();
        pooledClient = PooledWebClient.of(unpooledClient);
    }

    @Test
    void unpooled() {
        final AggregatedHttpResponse response = unpooledClient.get("/").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("Hi and goodbye!");
    }

    @Test
    void pooled() {
        final PooledAggregatedHttpResponse response = pooledClient.get("/").aggregateWithPooledObjects().join();
        try (SafeCloseable unused = response) {
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("Hi and goodbye!");
        }
    }
}

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

package com.linecorp.armeria.server.unsafe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.unsafe.PooledHttpRequest;
import com.linecorp.armeria.common.unsafe.PooledHttpResponse;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class PooledHttpServiceTest {

    private static final PooledHttpService SIMPLE = (ctx, req) -> {
        final CompletableFuture<HttpResponse> future =
                req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).thenApply(agg -> {
                    try (SafeCloseable unused = agg) {
                        assertThat(agg.contentUtf8()).isEqualTo("request");
                    }
                    return HttpResponse.of("content");
                });
        return PooledHttpResponse.of(HttpResponse.from(future));
    };

    private static final PooledHttpService WRAPPED = PooledHttpService.of((ctx, req) -> {
        // We are just a normal HttpService so should not be given a pooled request.
        assertThat(req).isNotInstanceOf(PooledHttpRequest.class);

        // We are a normal HttpService and return a non-pooled response. But users of the wrapping
        // PooledHttpService will be able to access it using pooled objects.
        final HttpResponse response = HttpResponse.of("content");
        assertThat(response).isNotInstanceOf(PooledHttpResponse.class);
        return response;
    });

    // Because interface default methods cannot be declared final, it is possible for a user to define this bad
    // PooledHttpService but since this is an advanced API let's trust them not to. We add this test to
    // demonstrate the behavior.
    private static final PooledHttpService NOT_ACTUALLY_POOLED = new PooledHttpService() {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of("content");
        }

        @Override
        public PooledHttpResponse serve(ServiceRequestContext ctx, PooledHttpRequest req) {
            throw new UnsupportedOperationException("Not called since base serve is overridden.");
        }
    };

    @RegisterExtension
    public static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/simple", SIMPLE);

            sb.service("/wrapped", (ctx, req) -> {
                final HttpResponse response = ((HttpService) WRAPPED).serve(ctx, req);
                // PooledHttpService response is always pooled.
                assertThat(response).isInstanceOf(PooledHttpResponse.class);
                return response;
            });

            sb.service("/not-actually-pooled", (ctx, req) -> {
                final HttpResponse response = ((HttpService) NOT_ACTUALLY_POOLED).serve(ctx, req);
                // Bad PooledHttpService that overrides incorrect method.
                assertThat(response).isNotInstanceOf(PooledHttpResponse.class);
                return response;
            });

            sb.decorator(LoggingService.builder().newDecorator());
        }
    };

    private WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
    }

    @Test
    void simple() {
        final AggregatedHttpResponse response = client.post("/simple", "request").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("content");
    }

    @Test
    void wrapped() {
        final AggregatedHttpResponse response = client.get("/wrapped").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("content");
    }

    @Test
    void notActallyPooled() {
        final AggregatedHttpResponse response = client.get("/not-actually-pooled").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("content");
    }
}

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.unsafe.PooledHttpRequest;
import com.linecorp.armeria.common.unsafe.PooledHttpResponse;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class SimplePooledDecoratingHttpServiceTest {

    private static final HttpService UNPOOLED_DELEGATE = (ctx, req) -> {
        // Even though we are a normal HttpService, the wrapper passes us a pooled request. This is OK since we
        // still just operate on the non-pooled API without problems.
        assertThat(req).isInstanceOf(PooledHttpRequest.class);
        return HttpResponse.from(
                req.aggregate().thenApply(agg -> HttpResponse.of("Hello " + agg.contentUtf8())));
    };

    private static final PooledHttpService POOLED_DELEGATE = (ctx, req) -> PooledHttpResponse.of(
            HttpResponse.from(
                    req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).thenApply(agg -> {
                        try (SafeCloseable unused = agg) {
                            return HttpResponse.of("Hello " + agg.contentUtf8());
                        }
                    })));

    private static final class PooledDecorator extends SimplePooledDecoratingHttpService {

        private PooledDecorator(HttpService delegate) {
            super(delegate);
        }

        @Override
        protected HttpResponse serve(PooledHttpService delegate, ServiceRequestContext ctx,
                                     PooledHttpRequest req) throws Exception {
            // Whether the decorator is applied to an unpooled or pooled delegate, it doesn't matter, we have
            // easy access to the unsafe API.
            return HttpResponse.from(
                    delegate.serve(ctx, req).aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc())
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
            sb.service("/unpooled-delegate", UNPOOLED_DELEGATE.decorate(PooledDecorator::new));

            sb.service("/pooled-delegate", POOLED_DELEGATE.decorate(PooledDecorator::new));
        }
    };

    private WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
    }

    @Test
    void unpooled() {
        final AggregatedHttpResponse response = client.post("/unpooled-delegate", "world").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("Hello world and goodbye!");
    }

    @Test
    void pooled() {
        final AggregatedHttpResponse response = client.post("/pooled-delegate", "earth").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("Hello earth and goodbye!");
    }
}

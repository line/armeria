/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class WebClientRequestPreparationTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/ping", (ctx, req) -> HttpResponse.of("pong"));
        }
    };

    @Test
    void setAttributes() {
        final AttributeKey<String> foo = AttributeKey.valueOf("foo");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final CompletableFuture<AggregatedHttpResponse> res =
                    WebClient.of(server.httpUri())
                             .prepare()
                             .get("/ping")
                             .attr(foo, "bar")
                             .execute()
                             .aggregate();
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.ownAttr(foo)).isEqualTo("bar");
            assertThat(res.join().contentUtf8()).isEqualTo("pong");
        }
    }

    @Test
    void setResponseTimeout() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final Duration timeout = Duration.ofSeconds(42);
            // Set an empty EndpointGroup to prevent initializing CancellingScheduler
            WebClient.of(SessionProtocol.H1C, EndpointGroup.of())
                     .prepare()
                     .get("/ping")
                     .responseTimeout(timeout)
                     .execute().aggregate();
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(timeout.toMillis());
        }
    }

    @Test
    void setMaxResponseLength() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final int maxResponseLength = 4242;
            WebClient.of(server.httpUri())
                     .prepare()
                     .get("/ping")
                     .maxResponseLength(maxResponseLength)
                     .execute()
                     .aggregate();
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.maxResponseLength()).isEqualTo(maxResponseLength);
        }
    }
}

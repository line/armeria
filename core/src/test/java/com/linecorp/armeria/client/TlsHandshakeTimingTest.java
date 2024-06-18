/*
 * Copyright 2024 LINE Corporation
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

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class TlsHandshakeTimingTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void httpsServerConnectionWithTlsSelfSigned() {
        final AtomicReference<ClientConnectionTimings> timingsHolder = new AtomicReference<>();
        try (ClientFactory clientFactory = ClientFactory.builder().tlsNoVerify().build()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(server.httpsUri())
                             .factory(clientFactory)
                             .decorator((delegate, ctx, req) -> {
                                 ctx.log().whenAvailable(RequestLogProperty.SESSION)
                                    .thenAccept(log -> timingsHolder.set(log.connectionTimings()));
                                 return delegate.execute(ctx, req);
                             })
                             .build()
                             .blocking()
                             .get("/");
            assertThat(res.status().code()).isEqualTo(200);
            assertThat(timingsHolder.get().tlsHandshakeStartTimeMicros()).isPositive();
            assertThat(timingsHolder.get().tlsHandshakeStartTimeMillis()).isPositive();
            assertThat(timingsHolder.get().tlsHandshakeDurationNanos()).isPositive();
        }
    }

    @Test
    void serverConnectionWithoutTls() {
        final AtomicReference<ClientConnectionTimings> timingsHolder = new AtomicReference<>();
        final AggregatedHttpResponse res =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             ctx.log().whenAvailable(RequestLogProperty.SESSION)
                                .thenAccept(log -> timingsHolder.set(log.connectionTimings()));
                             return delegate.execute(ctx, req);
                         })
                         .build()
                         .blocking()
                         .get("/");
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(timingsHolder.get().tlsHandshakeStartTimeMicros()).isEqualTo(-1);
        assertThat(timingsHolder.get().tlsHandshakeStartTimeMillis()).isEqualTo(-1);
        assertThat(timingsHolder.get().tlsHandshakeDurationNanos()).isEqualTo(-1);
    }
}

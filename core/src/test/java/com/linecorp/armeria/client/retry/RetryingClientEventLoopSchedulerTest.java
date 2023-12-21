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

package com.linecorp.armeria.client.retry;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.EventLoop;

class RetryingClientEventLoopSchedulerTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.http(0);
            sb.http(0);
            sb.service("/fail", (ctx, req) -> {
                throw new AnticipatedException();
            });
            sb.service("/ok", (ctx, req) -> {
                return HttpResponse.of(200);
            });
        }
    };

    @Test
    void shouldReturnCorrectEventLoop() {
        final List<Endpoint> endpoints = server.server().activePorts().values().stream()
                                               .map(port -> Endpoint.of(port.localAddress()))
                                               .collect(toImmutableList());
        assertThat(endpoints).hasSize(3);
        final Map<Endpoint, EventLoop> eventLoopMapping = new HashMap<>();

        for (Endpoint endpoint : endpoints) {
            // Acquire the event loops for each endpoint.
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse res = WebClient.of(SessionProtocol.H2C, endpoint)
                                                            .blocking()
                                                            .get("/ok");
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                eventLoopMapping.put(endpoint, captor.get().eventLoop().withoutContext());
            }
        }

        // Check that the event loops are correctly mapped for each attempt.
        final EndpointGroup endpointGroup = EndpointGroup.of(endpoints);
        final RetryRule retryRule = RetryRule.builder()
                                             .onServerErrorStatus()
                                             .thenBackoff(Backoff.withoutDelay());
        final BlockingWebClient client =
                WebClient.builder(SessionProtocol.H2C, endpointGroup)
                         // Make retries until the maxTotalAttempts is reached.
                         .responseTimeoutMillis(0)
                         .decorator(RetryingClient.newDecorator(
                                 RetryConfig.builder(retryRule)
                                            .maxTotalAttempts(6)
                                            .build()))
                         .build()
                         .blocking();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.get("/fail").status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            final List<RequestLogAccess> children = captor.get().log().children();
            assertThat(children.size()).isEqualTo(6);
            for (int i = 0; i < 6; i++) {
                final ClientRequestContext childCtx = (ClientRequestContext) children.get(i).context();
                assertThat(childCtx.eventLoop().withoutContext())
                        .isSameAs(eventLoopMapping.get(childCtx.endpoint()));
            }
        }
    }
}

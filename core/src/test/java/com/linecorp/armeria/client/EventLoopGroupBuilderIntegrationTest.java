/*
 * Copyright 2026 LINE Corporation
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.EventLoopGroup;

class EventLoopGroupBuilderIntegrationTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
        }
    };

    @Test
    void shouldWorkWithClientFactoryWorkerGroup() {
        final EventLoopGroup eventLoopGroup = EventLoopGroups
                .builder()
                .numThreads(4)
                .gracefulShutdown(Duration.ofMillis(0), Duration.ofMillis(0))
                .build();

        try (ClientFactory clientFactory = ClientFactory.builder()
                .workerGroup(eventLoopGroup, true)
                .build()) {

            final WebClient client = WebClient.builder(server.httpUri())
                                              .factory(clientFactory)
                                              .build();

            final AggregatedHttpResponse response = client.blocking().get("/");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("OK");
        }
    }
}

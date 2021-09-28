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
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeMap;

class ClientGoAwayGracefulShutdownTest {
    private static final AtomicInteger counter = new AtomicInteger();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/foo", (ctx, req) -> {
                if (counter.getAndIncrement() == 0) {
                    return HttpResponse.of("OK");
                } else {
                    return HttpResponse.delayed(HttpResponse.of("OK"), Duration.ofSeconds(5));
                }
            });
            // 1. A GOAWAY with Integer.MAX_VALUE will be sent in 1 second.
            sb.idleTimeout(Duration.ofSeconds(1));
            // 2. A GOAWAY with the last known stream identifier(3) in 2 seconds after the graceful period.
            sb.connectionDrainDuration(Duration.ofSeconds(2));
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @Test
    void gracefulShutdown() throws InterruptedException {
        final AtomicInteger opened = new AtomicInteger();
        final ConnectionPoolListener poolListener = new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                opened.incrementAndGet();
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {}
        };

        try (ClientFactory factory = ClientFactory.builder()
                                                  .idleTimeoutMillis(0)
                                                  .connectionPoolListener(poolListener)
                                                  .build()) {

            final WebClient client = WebClient.builder(server.httpUri())
                                              .factory(factory)
                                              .decorator(LoggingClient.newDecorator())
                                              .responseTimeoutMillis(0)
                                              .build();
            client.get("/foo").aggregate().join();
            // Trigger the idle timeout and wait for a connection to initiate the graceful shutdown.
            Thread.sleep(2000);
            assertThat(opened).hasValue(1);

            // A client should open another connection and
            // should not send a request using the closing connection.
            final AggregatedHttpResponse response = client.get("/foo").aggregate().join();
            assertThat(response.contentUtf8()).isEqualTo("OK");
            await().untilAtomic(opened, Matchers.is(2));
        }
    }
}

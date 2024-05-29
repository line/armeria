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
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertThrows;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeMap;

class Http2ClientConnectionHandlerTest {

    private static final long DELAY_RESPONSE_IN_MILLIS = 2000;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/delayed", (ctx, req) ->
                    HttpResponse.delayed(HttpResponse.of("OK"), Duration.ofMillis(DELAY_RESPONSE_IN_MILLIS)));
        }
    };

    @Test
    void requestHandlingSucceedsWhenGracefulShutdownIsLonger() {
        // Connection drain duration is longer than the response delay so the request is handled successfully
        final AtomicInteger opened = new AtomicInteger();
        final ConnectionPoolListener poolListener = getConnectionPoolListener(opened);
        final ClientFactory factory = ClientFactory.builder()
                                                   .http2GracefulShutdownTimeout(
                                                           Duration.ofMillis(DELAY_RESPONSE_IN_MILLIS + 2000))
                                                   .connectionPoolListener(poolListener)
                                                   .build();
        final HttpResponse res = WebClient.builder(server.httpUri())
                                          .decorator(LoggingClient.newDecorator())
                                          .responseTimeoutMillis(0)
                                          .factory(factory)
                                          .build()
                                          .get("/delayed");

        // Wait until connection is open, then close the factory before the response is fully received.
        await().until(() -> opened.get() > 0);
        factory.close();
        final AggregatedHttpResponse result = res.aggregate().join();
        assertThat(result.contentUtf8()).isEqualTo("OK");
    }

    @Test
    void requestHandlingFailsWhenGracefulShutdownIsShorter() throws InterruptedException {
        // Connection drain duration is shorter than the response delay so the request handling fails
        final AtomicInteger opened = new AtomicInteger();
        final ConnectionPoolListener poolListener = getConnectionPoolListener(opened);
        final ClientFactory factory = ClientFactory.builder()
                                                   .http2GracefulShutdownTimeoutMillis(100)
                                                   .connectionPoolListener(poolListener)
                                                   .build();
        final HttpResponse res = WebClient.builder(server.httpUri())
                                          .decorator(LoggingClient.newDecorator())
                                          .responseTimeoutMillis(0)
                                          .factory(factory)
                                          .build()
                                          .get("/delayed");

        // Wait until connection is open, then close the factory before the response is fully received.
        await().until(() -> opened.get() > 0);
        factory.close();
        assertThrows(CompletionException.class, () -> res.aggregate().join());
    }

    private static ConnectionPoolListener getConnectionPoolListener(AtomicInteger opened) {
        return new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                opened.incrementAndGet();
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {}
        };
    }
}

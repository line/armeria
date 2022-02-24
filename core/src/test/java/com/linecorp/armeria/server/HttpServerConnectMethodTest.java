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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeMap;

class HttpServerConnectMethodTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void connectMethodDisallowedInHttp1() {
        final AtomicInteger closedCount = new AtomicInteger();
        final AtomicInteger openedCount = new AtomicInteger();
        final ConnectionPoolListener poolListener = new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                openedCount.incrementAndGet();
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                closedCount.incrementAndGet();
            }
        };

        final ClientFactory factory = ClientFactory.builder()
                                                   .connectionPoolListener(poolListener)
                                                   .build();
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H1C))
                                          .factory(factory)
                                          .build();

        final AggregatedHttpResponse res1 = client
                .execute(HttpRequest.of(HttpMethod.CONNECT, "/"))
                .aggregate()
                .join();
        assertThat(res1.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        await().untilAsserted(() -> {
            assertThat(openedCount).hasValue(1);
            assertThat(closedCount).hasValue(1);
        });

        final AggregatedHttpResponse res2 = client
                .prepare()
                .method(HttpMethod.CONNECT)
                .path("/")
                .header(HttpHeaderNames.PROTOCOL, "websocket")
                .execute()
                .aggregate()
                .join();

        // HTTP/1 decoder will reject a header starts with `:` anyway.
        assertThat(res2.status()).isSameAs(HttpStatus.BAD_REQUEST);
        await().untilAsserted(() -> {
            assertThat(openedCount).hasValue(2);
            assertThat(closedCount).hasValue(2);
        });
        factory.closeAsync();
    }

    @Test
    void connectMethodDisallowedInHttp2() {
        final WebClient client = WebClient.of(server.uri(SessionProtocol.H2C));
        final AggregatedHttpResponse res1 = client
                .execute(HttpRequest.of(HttpMethod.CONNECT, "/"))
                .aggregate()
                .join();
        assertThat(res1.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);

        // However, a WebSocket handshake request should be allowed.
        final AggregatedHttpResponse res2 = client
                .prepare()
                .method(HttpMethod.CONNECT)
                .path("/")
                .header(HttpHeaderNames.PROTOCOL, "websocket")
                .execute()
                .aggregate()
                .join();
        assertThat(res2.status()).isSameAs(HttpStatus.OK);
    }
}

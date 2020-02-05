/*
 * Copyright 2018 LINE Corporation
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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.netty.util.AttributeMap;

/**
 * Makes sure Armeria HTTP client respects {@code MAX_CONCURRENT_STREAMS} HTTP/2 setting.
 */
public class HttpClientMaxConcurrentStreamTest {

    private static final String PATH = "/test";
    private static final int MAX_CONCURRENT_STREAMS = 3;

    private final Queue<CompletableFuture<HttpResponse>> responses = new ConcurrentLinkedQueue<>();

    @Rule
    public final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(PATH, (ctx, req) -> {
                final CompletableFuture<HttpResponse> f = new CompletableFuture<>();
                responses.add(f);
                return HttpResponse.from(f);
            });
            sb.http2MaxStreamsPerConnection(MAX_CONCURRENT_STREAMS);
        }
    };

    private final ConnectionPoolListener connectionPoolListenerWrapper = new ConnectionPoolListener() {
        @Override
        public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                   InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
            final ConnectionPoolListener connectionPoolListener =
                    HttpClientMaxConcurrentStreamTest.this.connectionPoolListener;
            if (connectionPoolListener != null) {
                connectionPoolListener.connectionOpen(protocol, remoteAddr, localAddr, attrs);
            }
        }

        @Override
        public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                     InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
            final ConnectionPoolListener connectionPoolListener =
                    HttpClientMaxConcurrentStreamTest.this.connectionPoolListener;
            if (connectionPoolListener != null) {
                connectionPoolListener.connectionClosed(protocol, remoteAddr, localAddr, attrs);
            }
        }
    };

    @Nullable
    private ClientFactory clientFactory;
    @Nullable
    private volatile ConnectionPoolListener connectionPoolListener;

    @Before
    public void setUp() {
        clientFactory = ClientFactory.builder()
                                     .workerGroup(EventLoopGroups.newEventLoopGroup(1), true)
                                     .connectionPoolListener(connectionPoolListenerWrapper)
                                     .build();
    }

    @After
    public void tearDown() {
        // Complete all uncompleted requests.
        for (;;) {
            final CompletableFuture<HttpResponse> f = responses.poll();
            if (f == null) {
                break;
            }
            f.complete(HttpResponse.of(200));
        }

        if (clientFactory != null) {
            clientFactory.close();
        }
    }

    @Test
    public void shouldCreateConnectionWhenExceedsMaxConcurrentStreams() throws Exception {
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                          .factory(clientFactory)
                                          .build();
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        connectionPoolListener = new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                opens.incrementAndGet();
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                closes.incrementAndGet();
            }
        };

        // Send (2 * MAX_CONCURRENT_STREAMS) requests to create 2 connections, never more and never less.
        final List<CompletableFuture<AggregatedHttpResponse>> receivedResponses = new ArrayList<>();
        final int NUM_CONNECTIONS = 2;
        for (int j = 0; j < NUM_CONNECTIONS; j++) {
            final int expectedOpens = j + 1;
            for (int i = 0; i < MAX_CONCURRENT_STREAMS; i++) {
                // Send a request.
                receivedResponses.add(client.get(PATH).aggregate());

                // Check the number of open and closed connections.
                await().untilAsserted(() -> {
                    assertThat(opens).hasValue(expectedOpens);
                    assertThat(closes).hasValue(0);
                });
            }
        }

        // Complete one request so that we have a connection with (MAX_CONCURRENT_STREAM - 1) active streams.
        responses.poll().complete(HttpResponse.of(200));
        await().until(() -> receivedResponses.stream().anyMatch(CompletableFuture::isDone));

        // Send a new request, which must not create a new connection but use an existing connection.
        client.get(PATH);

        // Wait for a while to make sure no new connection is created.
        for (int i = 0; i < 3; i++) {
            Thread.sleep(1000);
            assertThat(opens).hasValue(NUM_CONNECTIONS);
            assertThat(closes).hasValue(0);
        }
    }
}

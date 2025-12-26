/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.ConnectionEventListener.CloseHint;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeMap;
import io.netty.util.ReferenceCountUtil;

class ConnectionPoolKeepAliveTest {

    private static final AtomicBoolean closed = new AtomicBoolean();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.childChannelPipelineCustomizer(cb -> {
                cb.addFirst(
                        new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                if (closed.get()) {
                                    ReferenceCountUtil.release(msg);
                                    return;
                                }
                                ctx.fireChannelRead(msg);
                            }
                        });
            });
        }
    };

    @BeforeEach
    void beforeEach() {
        closed.set(false);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"H2C", "H1C"})
    void pingEventsRecorded(SessionProtocol protocol) throws Exception {
        final RecordingConnectionPoolListener listener = new RecordingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(listener)
                                                  .pingIntervalMillis(1000)
                                                  .build()) {
            final AggregatedHttpResponse res = WebClient.builder(protocol, server.httpEndpoint())
                                                        .factory(factory)
                                                        .build().blocking().get("/");
            assertThat(res.status().code()).isEqualTo(200);
            await().untilAsserted(() -> assertThat(listener.writeIds).isNotEmpty());
            await().untilAsserted(() -> assertThat(listener.ackIds).isNotEmpty());
            assertThat(listener.writeIds).containsAll(listener.ackIds);

            closed.set(true);
            await().untilAsserted(() -> assertThat(listener.closeHint())
                    .isEqualTo(CloseHint.PING_TIMEOUT.name()));
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"H2C", "H1C"})
    void idleEventRecorded(SessionProtocol protocol) throws Exception {
        final RecordingConnectionPoolListener listener = new RecordingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(listener)
                                                  .idleTimeoutMillis(50)
                                                  .build()) {
            final AggregatedHttpResponse res = WebClient.builder(protocol, server.httpEndpoint())
                                                        .factory(factory)
                                                        .build().blocking().get("/");
            assertThat(res.status().code()).isEqualTo(200);

            await().untilAsserted(() -> assertThat(listener.closeHint())
                    .isEqualTo(CloseHint.CONNECTION_IDLE.name()));
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"H2C", "H1C"})
    void maxAgeEventRecorded(SessionProtocol protocol) throws Exception {
        final RecordingConnectionPoolListener listener = new RecordingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(listener)
                                                  .maxConnectionAgeMillis(1000)
                                                  .build()) {
            final AggregatedHttpResponse res = WebClient.builder(protocol, server.httpEndpoint())
                                                        .factory(factory)
                                                        .build().blocking().get("/");
            assertThat(res.status().code()).isEqualTo(200);

            await().untilAsserted(() -> assertThat(listener.closeHint())
                    .isEqualTo(CloseHint.MAX_CONNECTION_AGE.name()));
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"H2C", "H1C"})
    void pingIdsAreRecorded(SessionProtocol protocol) throws Exception {
        final RecordingConnectionPoolListener listener = new RecordingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(listener)
                                                  .pingIntervalMillis(1000)
                                                  .build()) {
            final AggregatedHttpResponse res = WebClient.builder(protocol, server.httpEndpoint())
                                                        .factory(factory)
                                                        .build().blocking().get("/");
            assertThat(res.status().code()).isEqualTo(200);
            await().untilAsserted(() -> assertThat(listener.writeIds).hasSizeGreaterThanOrEqualTo(3));
            await().untilAsserted(() -> assertThat(listener.ackIds).hasSizeGreaterThanOrEqualTo(3));
            assertThat(listener.writeIds).containsAll(listener.ackIds);
        }
    }

    private static class RecordingConnectionPoolListener extends ConnectionPoolListenerAdapter {
        private final Set<Long> ackIds = new HashSet<>();
        private final Set<Long> writeIds = new HashSet<>();
        @Nullable
        private String closeHint;

        private String closeHint() {
            assertThat(closeHint).isNotNull();
            return closeHint;
        }

        @Override
        public void onPingAcknowledged(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs, long identifier)
                throws Exception {
            ackIds.add(identifier);
        }

        @Override
        public void onPingSent(SessionProtocol protocol, InetSocketAddress remoteAddr,
                               InetSocketAddress localAddr, AttributeMap attrs, long identifier)
                throws Exception {
            writeIds.add(identifier);
        }

        @Override
        public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                     InetSocketAddress localAddr, AttributeMap attrs, String closeHint)
                throws Exception {
            this.closeHint = closeHint;
        }
    }
}

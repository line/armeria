/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.server.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.CountingConnectionPoolListener;
import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.StreamTimeoutException;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketIdleTimeoutException;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;

class WebSocketServiceStreamTimeoutTest {
    private static final AttributeKey<CompletableFuture<Throwable>> INBOUND_CAUSE_FUT =
            AttributeKey.valueOf("inboundCauseFuture");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/timeout",
                       WebSocketService.builder(new Handler())
                                       .streamTimeout(Duration.ofSeconds(1))
                                       .build());
            sb.service("/keepalive",
                       WebSocketService.builder(new Handler())
                                       .streamTimeout(Duration.ofSeconds(2))
                                       .build());
        }
    };

    @Test
    void streamTimeoutH2EmitsCloseAndLogsCauses() throws InterruptedException {
        final URI uri = server.uri(SessionProtocol.H2C);
        final WebSocketClient client = WebSocketClient.of(uri);
        final WebSocketSession session = client.connect("/timeout").join();

        final List<WebSocketFrame> frames = session.inbound().collect().join();
        assertThat(frames).isNotEmpty();
        assertThat(frames.get(frames.size() - 1).type()).isSameAs(WebSocketFrameType.CLOSE);

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        assertThatThrownBy(Objects.requireNonNull(ctx.attr(INBOUND_CAUSE_FUT))::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StreamTimeoutException.class);

        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(Objects.requireNonNull(Objects.requireNonNull(log.requestCause())))
                .isInstanceOf(WebSocketIdleTimeoutException.class)
                .hasCauseInstanceOf(StreamTimeoutException.class);
        assertThat(Objects.requireNonNull(Objects.requireNonNull(log.responseCause())))
                .isInstanceOf(WebSocketIdleTimeoutException.class)
                .hasCauseInstanceOf(StreamTimeoutException.class);
    }

    @Test
    void streamTimeoutH1ClosesChannel() throws InterruptedException {
        final URI uri = server.uri(SessionProtocol.H1C);
        final CompletableFuture<Void> firstClosedFuture = new CompletableFuture<>();
        final CountingConnectionPoolListener connListener = new CountingConnectionPoolListener() {
            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs)
                    throws Exception {
                super.connectionClosed(protocol, remoteAddr, localAddr, attrs);
                firstClosedFuture.complete(null);
            }
        };
        try (ClientFactory cf = ClientFactory
                .builder()
                .connectionPoolListener(connListener)
                .build()) {
            final WebSocketClient client = WebSocketClient.builder(uri)
                                                          .factory(cf)
                                                          .build();
            final WebSocketSession session = client.connect("/timeout").join();
            final WebSocketWriter cWriter = session.outbound();
            session.inbound().subscribe(new Subscriber<WebSocketFrame>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketFrame f) {
                    if (f.type() == WebSocketFrameType.CLOSE) {
                        cWriter.close();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                }

                @Override
                public void onComplete() {
                }
            });
            assertThat(firstClosedFuture).succeedsWithin(10, TimeUnit.SECONDS);
            assertThat(connListener.closed()).isEqualTo(1);
        }
    }

    @Test
    void sessionIsKeptAliveWhenFramesArriveWithinIdleTimeout() throws InterruptedException {
        final URI uri = server.uri(SessionProtocol.H2C);
        final WebSocketClient client = WebSocketClient.of(uri);
        final WebSocketSession session = client.connect("/keepalive").join();

        final WebSocketWriter cWriter = session.outbound();
        session.context().eventLoop().schedule(() -> cWriter.write(WebSocketFrame.ofText("tick-1")),
                                               1, TimeUnit.SECONDS);
        session.context().eventLoop().schedule(() -> cWriter.write(WebSocketFrame.ofText("tick-2")),
                                               2, TimeUnit.SECONDS);
        session.context().eventLoop().schedule(() -> cWriter.close(),
                                               3, TimeUnit.SECONDS);

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        assertThat(Objects.requireNonNull(ctx.attr(INBOUND_CAUSE_FUT)).join()).isNull();

        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.requestCause()).isNull();
        assertThat(log.responseCause()).isNull();
    }

    static final class Handler implements WebSocketServiceHandler {
        @Override
        public WebSocket handle(ServiceRequestContext ctx, WebSocket in) {
            final WebSocketWriter writer = WebSocket.streaming();
            final CompletableFuture<Throwable> inboundCause = new CompletableFuture<>();
            ctx.setAttr(INBOUND_CAUSE_FUT, inboundCause);
            in.subscribe(new Subscriber<WebSocketFrame>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketFrame frame) {
                }

                @Override
                public void onError(Throwable t) {
                    inboundCause.completeExceptionally(t);
                }

                @Override
                public void onComplete() {
                    inboundCause.complete(null);
                    writer.close();
                }
            });

            return writer;
        }
    }
}

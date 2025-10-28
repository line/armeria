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

package com.linecorp.armeria.client.websocket;

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

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.CountingConnectionPoolListener;
import com.linecorp.armeria.common.InboundCompleteException;
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
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.server.websocket.WebSocketServiceHandler;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;

class WebSocketClientStreamTimeoutTest {
    private static final AttributeKey<CompletableFuture<List<WebSocketFrame>>> FRAMES_FUT =
            AttributeKey.valueOf("framesFuture");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/test", WebSocketService.of(new Handler()));
            sb.service("/keepalive", WebSocketService.of(new KeepAliveHandler()));
        }
    };

    @Test
    void clientStreamTimeoutH2EmitsCloseAndLogsCauses() throws InterruptedException {
        final URI uri = server.uri(SessionProtocol.H2C);
        final WebSocketClient client = WebSocketClient.builder(uri)
                                                      .streamTimeout(Duration.ofSeconds(1))
                                                      .build();
        final WebSocketSession session = client.connect("/test").join();
        session.outbound();

        final CompletableFuture<List<WebSocketFrame>> future = session.inbound().collect();
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StreamTimeoutException.class);

        final ServiceRequestContext sCtx = server.requestContextCaptor().take();
        final List<WebSocketFrame> frames = Objects.requireNonNull(sCtx.attr(FRAMES_FUT)).join();
        assertThat(frames).isNotEmpty();
        assertThat(frames.get(frames.size() - 1).type()).isSameAs(WebSocketFrameType.CLOSE);

        final ClientRequestContext ctx = session.context();
        final RequestLog log = ctx.log().whenComplete().join();

        assertThat(Objects.requireNonNull(Objects.requireNonNull(log.responseCause())))
                .isInstanceOf(InboundCompleteException.class)
                .hasCauseInstanceOf(WebSocketIdleTimeoutException.class);
        assertThat(Objects.requireNonNull(Objects.requireNonNull(log.requestCause())))
                .isInstanceOf(InboundCompleteException.class)
                .hasCauseInstanceOf(WebSocketIdleTimeoutException.class);
    }

    @Test
    void clientStreamTimeoutH1ClosesChannel() {
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
                                                          .streamTimeout(Duration.ofSeconds(1))
                                                          .build();
            final WebSocketSession session = client.connect("/test").join();
            session.outbound();

            final CompletableFuture<List<WebSocketFrame>> future = session.inbound().collect();
            assertThatThrownBy(future::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(StreamTimeoutException.class);

            assertThat(firstClosedFuture).succeedsWithin(10, TimeUnit.SECONDS);
            assertThat(connListener.closed()).isEqualTo(1);
        }
    }

    @Test
    void sessionIsKeptAliveWhenFramesArriveWithinIdleTimeout() {
        final URI uri = server.uri(SessionProtocol.H2C);
        final WebSocketClient client = WebSocketClient.builder(uri)
                                                      .streamTimeout(Duration.ofMillis(300))
                                                      .build();

        final WebSocketSession session = client.connect("/keepalive").join();
        final List<WebSocketFrame> frames = session.inbound().collect().join();
        assertThat(frames).isNotEmpty();
        assertThat(frames.size()).isEqualTo(3);
        assertThat(frames.get(frames.size() - 1).type()).isSameAs(WebSocketFrameType.CLOSE);

        final ClientRequestContext ctx = session.context();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.requestCause()).isNull();
        assertThat(log.responseCause()).isNull();
    }

    static final class Handler implements WebSocketServiceHandler {
        @Override
        public WebSocket handle(ServiceRequestContext ctx, WebSocket in) {
            final WebSocketWriter writer = WebSocket.streaming();

            final CompletableFuture<List<WebSocketFrame>> fut = new CompletableFuture<>();
            ctx.setAttr(FRAMES_FUT, fut);

            in.collect().whenComplete((webSocketFrames, cause) -> {
               if (cause == null) {
                   fut.complete(webSocketFrames);
               } else {
                   fut.completeExceptionally(cause);
               }
               writer.close();
            });

            return writer;
        }
    }

    static final class KeepAliveHandler implements WebSocketServiceHandler {

        @Override
        public WebSocket handle(ServiceRequestContext ctx, WebSocket in) {
            final WebSocketWriter writer = WebSocket.streaming();
            ctx.eventLoop().schedule(() -> writer.write(WebSocketFrame.ofText("tick-1")),
                                     100, TimeUnit.MILLISECONDS);
            ctx.eventLoop().schedule(() -> writer.write(WebSocketFrame.ofText("tick-2")),
                                     250, TimeUnit.MILLISECONDS);
            ctx.eventLoop().schedule(() -> writer.close(),
                                     400, TimeUnit.MILLISECONDS);
            return writer;
        }
    }
}

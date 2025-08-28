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

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.StreamTimeoutException;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.server.websocket.WebSocketServiceHandler;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

class WebSocketClientStreamTimeoutTest {
    private static final AttributeKey<CompletableFuture<List<WebSocketFrame>>> FRAMES_FUT =
            AttributeKey.valueOf("framesFuture");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/test", WebSocketService.of(new Handler()));
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
        try {
            session.inbound().collect().join();
        } catch (Exception ignore) {
            // expected: stream timeout
        }

        final ServiceRequestContext sCtx = server.requestContextCaptor().take();
        final List<WebSocketFrame> frames = Objects.requireNonNull(sCtx.attr(FRAMES_FUT)).join();
        assertThat(frames).isNotEmpty();
        assertThat(frames.get(frames.size() - 1).type()).isSameAs(WebSocketFrameType.CLOSE);

        final ClientRequestContext ctx = session.context();
        final RequestLog log = ctx.log().whenComplete().join();

        assertThat(Objects.requireNonNull(Objects.requireNonNull(log.responseCause()).getCause()))
                .isInstanceOf(StreamTimeoutException.class);
        assertThat(Objects.requireNonNull(Objects.requireNonNull(log.requestCause()).getCause()))
                .isInstanceOf(StreamTimeoutException.class);
    }

    @Test
    void clientStreamTimeoutH1ClosesChannel() {
        final URI uri = server.uri(SessionProtocol.H1C);
        final WebSocketClient client = WebSocketClient.builder(uri)
                                                      .streamTimeout(Duration.ofSeconds(1))
                                                      .build();
        final WebSocketSession session = client.connect("/test").join();
        session.outbound();
        try {
            session.inbound().collect().join();
        } catch (Exception ignore) {
            // expected: stream timeout
        }

        final ClientRequestContext ctx = session.context();
        final Channel ch = ctx.log().whenAvailable(RequestLogProperty.SESSION).join().channel();

        assertThat(Objects.requireNonNull(ch).closeFuture()).succeedsWithin(10, TimeUnit.SECONDS);
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
}

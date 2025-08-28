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

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
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
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.Channel;

class WebSocketServiceStreamTimeoutTest {
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/timeout",
                       WebSocketService.builder(new Handler())
                                       .streamTimeout(Duration.ofSeconds(1))
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
        final RequestLog log = ctx.log().whenComplete().join();

        assertThat(Objects.requireNonNull(Objects.requireNonNull(log.requestCause()).getCause()))
                .isInstanceOf(StreamTimeoutException.class);
        assertThat(Objects.requireNonNull(Objects.requireNonNull(log.responseCause()).getCause()))
                .isInstanceOf(StreamTimeoutException.class);
    }

    @Test
    void streamTimeoutH1ClosesChannel() throws InterruptedException {
        final URI uri = server.uri(SessionProtocol.H1C);
        final WebSocketClient client = WebSocketClient.of(uri);
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

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final Channel ch = ctx.log().whenAvailable(RequestLogProperty.SESSION).join().channel();
        assertThat(Objects.requireNonNull(ch).closeFuture()).succeedsWithin(20, TimeUnit.SECONDS);
    }

    static final class Handler implements WebSocketServiceHandler {
        @Override
        public WebSocket handle(ServiceRequestContext ctx, WebSocket in) {
            final WebSocketWriter writer = WebSocket.streaming();
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
                }

                @Override
                public void onComplete() {
                    writer.close();
                }
            });

            return writer;
        }
    }
}

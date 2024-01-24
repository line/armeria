/*
 * Copyright 2023 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.server.websocket.WebSocketServiceHandler;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class WebSocketClientAggregateContinuationTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", WebSocketService.of(new WebSocketEchoHandler()));
        }
    };

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void aggregateFrames(boolean aggregate) throws InterruptedException {
        final WebSocketClient webSocketClient =
                WebSocketClient.builder(server.httpUri())
                               .aggregateContinuation(aggregate)
                               .build();
        final WebSocketSession webSocketSession = webSocketClient.connect("/").join();

        final WebSocketWriter outbound = webSocketSession.outbound();
        outbound.write(WebSocketFrame.ofText("Hello", false));
        outbound.write(WebSocketFrame.ofContinuation(" wor", false));
        outbound.write(WebSocketFrame.ofContinuation("ld!", true));

        final WebSocketInboundTestHandler inboundHandler = new WebSocketInboundTestHandler(
                webSocketSession.inbound(), SessionProtocol.H2C);

        WebSocketFrame frame;
        if (aggregate) {
            frame = inboundHandler.inboundQueue().take();
            assertThat(frame).isEqualTo(WebSocketFrame.ofText("Hello world!"));
        } else {
            frame = inboundHandler.inboundQueue().take();
            assertThat(frame).isEqualTo(WebSocketFrame.ofText("Hello", false));
            frame = inboundHandler.inboundQueue().take();
            assertThat(frame).isEqualTo(WebSocketFrame.ofContinuation(" wor", false));
            frame = inboundHandler.inboundQueue().take();
            assertThat(frame).isEqualTo(WebSocketFrame.ofContinuation("ld!"));
        }

        frame = inboundHandler.inboundQueue().poll(1, TimeUnit.SECONDS);
        assertThat(frame).isNull();

        outbound.write(WebSocketFrame.ofBinary("Hello".getBytes(), false));
        outbound.write(WebSocketFrame.ofContinuation(" wor".getBytes(), false));
        outbound.write(WebSocketFrame.ofContinuation("ld!".getBytes(), true));

        if (aggregate) {
            frame = inboundHandler.inboundQueue().take();
            assertThat(frame).isEqualTo(WebSocketFrame.ofBinary("Hello world!".getBytes()));
        } else {
            frame = inboundHandler.inboundQueue().take();
            assertThat(frame).isEqualTo(WebSocketFrame.ofBinary("Hello".getBytes(), false));
            frame = inboundHandler.inboundQueue().take();
            assertThat(frame).isEqualTo(WebSocketFrame.ofContinuation(" wor".getBytes(), false));
            frame = inboundHandler.inboundQueue().take();
            assertThat(frame).isEqualTo(WebSocketFrame.ofContinuation("ld!".getBytes()));
        }

        frame = inboundHandler.inboundQueue().poll(1, TimeUnit.SECONDS);
        assertThat(frame).isNull();

        outbound.close(WebSocketCloseStatus.NORMAL_CLOSURE);
        frame = inboundHandler.inboundQueue().take();
        assertThat(frame).isEqualTo(WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE));
        inboundHandler.completionFuture().join();
        await().until(outbound::isComplete);
    }

    static final class WebSocketEchoHandler implements WebSocketServiceHandler {

        @Override
        public WebSocket handle(ServiceRequestContext ctx, WebSocket in) {
            final WebSocketWriter writer = WebSocket.streaming();
            in.subscribe(new Subscriber<WebSocketFrame>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketFrame webSocketFrame) {
                    if (webSocketFrame.type() != WebSocketFrameType.PING &&
                        webSocketFrame.type() != WebSocketFrameType.PONG) {
                        writer.write(webSocketFrame);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    writer.close(t);
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

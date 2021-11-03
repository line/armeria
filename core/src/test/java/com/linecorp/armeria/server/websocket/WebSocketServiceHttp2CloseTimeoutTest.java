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
package com.linecorp.armeria.server.websocket;

import static com.linecorp.armeria.server.websocket.WebSocketServiceTest.checkCloseFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.websocket.WebSocketServiceTest.AbstractWebSocketHandler;
import com.linecorp.armeria.server.websocket.WebSocketServiceTest.BodySubscriber;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http.HttpHeaderValues;

class WebSocketServiceHttp2CloseTimeoutTest {

    private static final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(true);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final AbstractWebSocketHandler handler = new AbstractWebSocketHandler() {
                @Override
                void onOpen(WebSocketWriter writer) {
                    writer.close();
                }
            };
            sb.service("/noCloseTimeout", WebSocketService.builder(handler)
                                                          .closeTimeoutMillis(Long.MAX_VALUE)
                                                          .build());
            sb.service("/2000MillisTimeout", WebSocketService.builder(handler)
                                                             .closeTimeoutMillis(2000)
                                                             .build());
        }
    };

    private static WebClient client;

    @BeforeAll
    static void setUp() {
        client = WebClient.of(server.uri(SessionProtocol.H2C));
    }

    @Test
    void normalCloseWhenCloseFrameSentAndReceived() throws InterruptedException {
        final HttpRequestWriter requestWriter =
                HttpRequest.streaming(webSocketUpgradeHeaders("/noCloseTimeout"));
        final ClientRequestContext ctx = ClientRequestContext.of(requestWriter);

        requestWriter.write(HttpData.wrap(encoder.encode(
                ctx, WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE))));

        final BodySubscriber bodySubscriber = new BodySubscriber();
        client.execute(requestWriter).split().body().subscribe(bodySubscriber);

        bodySubscriber.whenComplete.join();
        checkCloseFrame(bodySubscriber.messageQueue.take());
        await().until(() -> requestWriter.whenComplete().isDone());
    }

    @Test
    void closeAfterTimeoutWhenCloseFrameIsNotSent() throws InterruptedException {
        final HttpRequestWriter requestWriter =
                HttpRequest.streaming(webSocketUpgradeHeaders("/2000MillisTimeout"));
        final BodySubscriber bodySubscriber = new BodySubscriber();
        client.execute(requestWriter).split().body().subscribe(bodySubscriber);

        await().atLeast(1000, TimeUnit.MILLISECONDS) // buffer 1000 milliseconds
               .until(() -> requestWriter.whenComplete().isCompletedExceptionally());
        // Request is aborted because the client didn't send the close frame.
        assertThatThrownBy(() -> requestWriter.whenComplete().join())
                .hasCauseInstanceOf(AbortedStreamException.class);
        checkCloseFrame(bodySubscriber.messageQueue.take());
        // Response is completed normally because the client received close frame.
        bodySubscriber.whenComplete.join();
    }

    @Test
    void notClosedIfCloseFrameIsNotSentWhenNoCloseTimeout() throws InterruptedException {
        final HttpRequestWriter requestWriter =
                HttpRequest.streaming(webSocketUpgradeHeaders("/noCloseTimeout"));
        final BodySubscriber bodySubscriber = new BodySubscriber();
        client.execute(requestWriter).split().body().subscribe(bodySubscriber);

        Thread.sleep(3000);
        // The request and response are not complete.
        assertThat(requestWriter.whenComplete().isDone()).isFalse();
        assertThat(bodySubscriber.whenComplete.isDone()).isFalse();
    }

    private static RequestHeaders webSocketUpgradeHeaders(String path) {
        return RequestHeaders.builder(HttpMethod.CONNECT, path)
                             .add(HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET.toString())
                             .addInt(HttpHeaderNames.SEC_WEBSOCKET_VERSION, 13)
                             .build();
    }
}

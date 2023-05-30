/*
 * Copyright 2022 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketServiceTest.AbstractWebSocketHandler;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http.HttpHeaderValues;

class WebSocketServiceHttp2RequestCompleteTest {

    private static final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(true);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final AbstractWebSocketHandler immediateClosingHandler = new AbstractWebSocketHandler() {
                @Override
                void onOpen(WebSocketWriter writer) {
                    writer.close();
                }
            };
            sb.route()
              .path("/2000MillisTimeout")
              .requestAutoAbortDelayMillis(2000)
              .build(WebSocketService.of(immediateClosingHandler));
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
                HttpRequest.streaming(webSocketUpgradeHeaders("/2000MillisTimeout"));
        final ClientRequestContext ctx = ClientRequestContext.of(requestWriter);

        requestWriter.write(HttpData.wrap(encoder.encode(
                ctx, WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE))));
        requestWriter.close();

        final BodySubscriber bodySubscriber = new BodySubscriber();
        final SplitHttpResponse split = client.execute(requestWriter).split();
        assertThat(split.headers().join().status()).isSameAs(HttpStatus.OK);
        split.body().subscribe(bodySubscriber);

        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        await().atMost(1000, TimeUnit.MILLISECONDS) // buffer 1000 milliseconds
               .until(() -> sctx.request().isComplete());
        bodySubscriber.whenComplete.join();
        checkCloseFrame(bodySubscriber.messageQueue.take(), WebSocketCloseStatus.NORMAL_CLOSURE);
    }

    @Test
    void closeAfterTimeoutWhenCloseFrameIsNotSent() throws InterruptedException {
        final HttpRequestWriter requestWriter =
                HttpRequest.streaming(webSocketUpgradeHeaders("/2000MillisTimeout"));
        final BodySubscriber bodySubscriber = new BodySubscriber();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final SplitHttpResponse split = client.execute(requestWriter).split();
            split.headers().thenAccept(headers -> {
                assertThat(headers.status()).isSameAs(HttpStatus.OK);
                // Update the request to prevent the request from being completed.
                captor.get().updateRequest(HttpRequest.of(HttpMethod.GET, "/"));
            });
            split.body().subscribe(bodySubscriber);
        }

        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        await().atLeast(1000, TimeUnit.MILLISECONDS) // buffer 1000 milliseconds
               .until(() -> sctx.request().isComplete());
        bodySubscriber.whenComplete.join();
        checkCloseFrame(bodySubscriber.messageQueue.take(), WebSocketCloseStatus.NORMAL_CLOSURE);
    }

    private static RequestHeaders webSocketUpgradeHeaders(String path) {
        return RequestHeaders.builder(HttpMethod.CONNECT, path)
                             .add(HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET.toString())
                             .add(HttpHeaderNames.ORIGIN, "http://" + server.httpEndpoint().authority())
                             .addInt(HttpHeaderNames.SEC_WEBSOCKET_VERSION, 13)
                             .build();
    }

    static final class BodySubscriber implements Subscriber<HttpData> {

        final CompletableFuture<Void> whenComplete = new CompletableFuture<>();

        final BlockingQueue<HttpData> messageQueue = new LinkedBlockingQueue<>();

        @Override
        public void onSubscribe(Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpData httpData) {
            messageQueue.add(httpData);
        }

        @Override
        public void onError(Throwable t) {
            whenComplete.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            whenComplete.complete(null);
        }
    }
}

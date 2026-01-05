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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.websocket.WebSocketClientStreamTimeoutTest.Handler;
import com.linecorp.armeria.common.InboundCompleteException;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class WebSocketClientInboundCompleteExceptionTest {
    private static final AttributeKey<CompletableFuture<List<WebSocketFrame>>> FRAMES_FUT =
            AttributeKey.valueOf("framesFuture");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/test",
                       WebSocketService.of(new Handler()));
        }
    };

    @Test
    void clientInboundCancelAbortsOutboundWithMappedCauseAndSendsClose() throws InterruptedException {
        final WebSocketClient client = WebSocketClient.of(server.uri(SessionProtocol.H2C));
        final WebSocketSession session = client.connect("/test").join();
        session.outbound();
        session.inbound().subscribe(new Subscriber<WebSocketFrame>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
                s.cancel();
            }

            @Override
            public void onNext(WebSocketFrame frame) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });

        final ServiceRequestContext sCtx = server.requestContextCaptor().take();
        final List<WebSocketFrame> frames = Objects.requireNonNull(sCtx.attr(FRAMES_FUT)).join();
        assertThat(frames).isNotEmpty();
        assertThat(frames.get(frames.size() - 1).type()).isSameAs(WebSocketFrameType.CLOSE);

        final ClientRequestContext ctx = session.context();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(Objects.requireNonNull(log.responseCause())).isInstanceOf(InboundCompleteException.class);
        assertThat(Objects.requireNonNull(log.requestCause())).isInstanceOf(InboundCompleteException.class);
    }

    @Test
    void clientInboundAbortAbortsOutboundWithMappedCauseAndSendsClose() throws InterruptedException {
        final WebSocketClient client = WebSocketClient.of(server.uri(SessionProtocol.H2C));
        final WebSocketSession session = client.connect("/test").join();
        session.outbound();
        session.inbound().subscribe(new Subscriber<WebSocketFrame>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
                session.inbound().abort();
            }

            @Override
            public void onNext(WebSocketFrame frame) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });

        final ServiceRequestContext sCtx = server.requestContextCaptor().take();
        final List<WebSocketFrame> frames = Objects.requireNonNull(sCtx.attr(FRAMES_FUT)).join();
        assertThat(frames).isNotEmpty();
        assertThat(frames.get(frames.size() - 1).type()).isSameAs(WebSocketFrameType.CLOSE);

        final ClientRequestContext ctx = session.context();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(Objects.requireNonNull(log.responseCause())).isInstanceOf(InboundCompleteException.class);
        assertThat(Objects.requireNonNull(log.requestCause())).isInstanceOf(InboundCompleteException.class);
    }
}

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

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.internal.common.websocket.WebSocketCloseHandler;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;
import com.linecorp.armeria.server.ServiceRequestContext;

final class WebSocketResponseSubscriber implements Subscriber<WebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketResponseSubscriber.class);

    private final ServiceRequestContext ctx;
    private final HttpResponseWriter writer;
    private final WebSocketFrameEncoder encoder;
    private final WebSocketCloseHandler webSocketCloseHandler;
    @Nullable
    private Subscription subscription;

    WebSocketResponseSubscriber(ServiceRequestContext ctx, HttpResponseWriter writer,
                                WebSocketFrameEncoder encoder, WebSocketCloseHandler webSocketCloseHandler) {
        this.ctx = ctx;
        this.writer = writer;
        this.encoder = encoder;
        this.webSocketCloseHandler = webSocketCloseHandler;
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription = s;
        s.request(1);
    }

    @Override
    public void onNext(WebSocketFrame webSocketFrame) {
        assert subscription != null;
        try {
            if (webSocketCloseHandler.isCloseFrameSent()) {
                webSocketFrame.close();
                subscription.cancel();
                return;
            }

            if (!writer.tryWrite(HttpData.wrap(encoder.encode(ctx, webSocketFrame)))) {
                subscription.cancel();
                return;
            }

            if (webSocketFrame.type() == WebSocketFrameType.CLOSE) {
                logger.trace("Close frame is sent: {}", webSocketFrame);
                webSocketCloseHandler.closeFrameSent();
                subscription.cancel();
                return;
            }
            writer.whenConsumed().thenRun(() -> subscription.request(1));
        } catch (Throwable t) {
            subscription.cancel();
            webSocketCloseHandler.closeStreams(t, false);
        }
    }

    @Override
    public void onError(Throwable t) {
        webSocketCloseHandler.closeStreams(t, false);
    }

    @Override
    public void onComplete() {
        if (webSocketCloseHandler.isCloseFrameSent()) {
            return;
        }
        // Consider creating a static field for close frame HttpData.
        final CloseWebSocketFrame closeFrame = WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE);
        if (writer.tryWrite(HttpData.wrap(encoder.encode(ctx, closeFrame)))) {
            logger.trace("Close frame is sent: {}", closeFrame);
            webSocketCloseHandler.closeFrameSent();
        }
    }
}

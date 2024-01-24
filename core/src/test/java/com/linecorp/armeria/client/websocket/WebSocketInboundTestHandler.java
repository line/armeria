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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketFrame;

public final class WebSocketInboundTestHandler {

    private final ArrayBlockingQueue<WebSocketFrame> inboundQueue = new ArrayBlockingQueue<>(4);
    private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

    public WebSocketInboundTestHandler(WebSocket inbound, SessionProtocol protocol) {
        inbound.subscribe(new Subscriber<WebSocketFrame>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WebSocketFrame webSocketFrame) {
                inboundQueue.add(webSocketFrame);
            }

            @Override
            public void onError(Throwable t) {
                if (protocol.isExplicitHttp1()) {
                    // After receiving a close frame, ClosedSessionException can be raised for HTTP/1.1
                    // before onComplete is called.
                    assertThat(t).isExactlyInstanceOf(ClosedSessionException.class);
                }
                completionFuture.complete(null);
            }

            @Override
            public void onComplete() {
                completionFuture.complete(null);
            }
        });
    }

    public ArrayBlockingQueue<WebSocketFrame> inboundQueue() {
        return inboundQueue;
    }

    public CompletableFuture<Void> completionFuture() {
        return completionFuture;
    }
}

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
package com.linecorp.armeria.internal.common.websocket;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketFrame;

import io.netty.util.concurrent.EventExecutor;

public final class WebSocketWrapper implements WebSocket {

    private final StreamMessage<WebSocketFrame> delegate;

    public WebSocketWrapper(StreamMessage<WebSocketFrame> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public long demand() {
        return delegate.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return delegate.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super WebSocketFrame> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        delegate.subscribe(subscriber, executor, options);
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(cause);
    }
}

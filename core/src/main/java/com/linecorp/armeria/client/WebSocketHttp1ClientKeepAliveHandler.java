/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.internal.common.DelegatingConnectionEventListener;
import com.linecorp.armeria.internal.common.Http1KeepAliveHandler;

import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

final class WebSocketHttp1ClientKeepAliveHandler extends Http1KeepAliveHandler {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketHttp1ClientKeepAliveHandler.class);

    WebSocketHttp1ClientKeepAliveHandler(Channel channel, Timer keepAliveTimer,
                                         DelegatingConnectionEventListener connectionEventListener) {

        // WebSocketHttp1ClientKeepAliveHandler is mostly noop because
        // - hasRequestsInProgress is always true for WebSocket
        // - a Ping frame is not sent by the keepAliveHandler but by the upper layer.
        // TODO(minwoox): Provide a dedicated KeepAliveHandler to the upper layer (e.g. WebSocketClient)
        //                that handles Ping frames for WebSocket.
        super(channel, "client", keepAliveTimer, connectionEventListener, 0, 0, 0,
              0, false);
    }

    @Override
    protected Logger logger() {
        return logger;
    }

    @Override
    protected ChannelFuture writePing() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean pingResetsPreviousPing() {
        return false;
    }

    @Override
    protected boolean hasRequestsInProgress() {
        return false;
    }
}

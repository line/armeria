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
package com.linecorp.armeria.common.websocket;

import io.netty.buffer.ByteBuf;

final class PingWebSocketFrame extends WebSocketFrameWrapper {

    static final PingWebSocketFrame emptyPing = new PingWebSocketFrame(new byte[0]);

    PingWebSocketFrame(byte[] binary) {
        super(new ByteArrayWebSocketFrame(WebSocketFrameType.PING, binary, true));
    }

    PingWebSocketFrame(ByteBuf binary) {
        super(new ByteBufWebSocketFrame(WebSocketFrameType.PING, binary, true));
    }
}

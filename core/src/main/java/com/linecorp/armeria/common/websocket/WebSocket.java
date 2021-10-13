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

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * A {@link StreamMessage} that publishes {@link WebSocketFrame}s.
 */
@UnstableApi
public interface WebSocket extends StreamMessage<WebSocketFrame> {

    /**
     * Returns a new {@link WebSocketWriter} that you can write {@link WebSocketFrame}s to send to the peer.
     */
    static WebSocketWriter streaming() {
        return new DefaultWebSocket();
    }
}

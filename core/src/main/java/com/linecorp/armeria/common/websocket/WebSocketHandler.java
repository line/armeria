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
package com.linecorp.armeria.common.websocket;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.websocket.WebSocketServiceHandler;

/**
 * Implement this interface to handle incoming {@link WebSocketFrame}s from the peer and
 * send {@link WebSocketFrame}s to the peer.
 *
 * @see WebSocketServiceHandler
 */
@UnstableApi
@FunctionalInterface
public interface WebSocketHandler<T extends RequestContext> {

    /**
     * Handles the incoming {@link WebSocket} and returns {@link WebSocket} created via
     * {@link WebSocket#streaming()} to send {@link WebSocketFrame}s.
     */
    WebSocket handle(T ctx, WebSocket in);
}

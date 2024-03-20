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

package com.linecorp.armeria.server.websocket;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A WebSocket protocol handler for {@link WebSocketService}.
 */
@UnstableApi
public interface WebSocketProtocolHandler {

    /**
     * Upgrades the given {@link HttpRequest} to a {@link WebSocket}.
     *
     * <p>If the upgrade succeeds, {@link WebSocketUpgradeResult#ofSuccess()} is returned.
     * If the upgrade fails, {@link WebSocketUpgradeResult#ofFailure(HttpResponse)} is returned.
     */
    WebSocketUpgradeResult upgrade(ServiceRequestContext ctx, HttpRequest req) throws Exception;

    /**
     * Decodes the specified {@link HttpRequest} to a {@link WebSocket}.
     */
    WebSocket decode(ServiceRequestContext ctx, HttpRequest req);

    /**
     * Encodes the specified {@link WebSocket} to an {@link HttpResponse}.
     */
    HttpResponse encode(ServiceRequestContext ctx, WebSocket out);
}

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
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceOptions;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An {@link HttpService} that supports <a href="https://datatracker.ietf.org/doc/html/rfc6455">
 * The WebSocket Protocol</a>.
 * This service has a few different default values for {@link ServiceConfig} from a normal {@link HttpService}
 * because of the nature of WebSocket. See {@link WebSocketServiceBuilder} for more information.
 */
@UnstableApi
public interface WebSocketService extends HttpService {

    /**
     * Returns a new {@link WebSocketService} with the {@link WebSocketServiceHandler}.
     */
    static WebSocketService of(WebSocketServiceHandler handler) {
        return new WebSocketServiceBuilder(handler).build();
    }

    /**
     * Returns a new {@link WebSocketServiceBuilder} with the {@link WebSocketServiceHandler}.
     */
    static WebSocketServiceBuilder builder(WebSocketServiceHandler handler) {
        return new WebSocketServiceBuilder(handler);
    }

    @Override
    default HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final WebSocketUpgradeResult upgradeResult = protocolHandler().upgrade(ctx, req);
        if (!upgradeResult.isSuccess()) {
            return upgradeResult.fallbackResponse();
        }

        final WebSocket in = protocolHandler().decode(ctx, req);
        final WebSocket out = serve(ctx, in);
        return protocolHandler().encode(ctx, out);
    }

    /**
     * Serves the specified {@link WebSocket} and returns the {@link WebSocket} to send responses.
     */
    WebSocket serve(ServiceRequestContext ctx, WebSocket in) throws Exception;

    /**
     * Returns the {@link WebSocketProtocolHandler} of this service.
     */
    WebSocketProtocolHandler protocolHandler();

    @Override
    default ServiceOptions options() {
        return WebSocketServiceBuilder.DEFAULT_OPTIONS;
    }
}

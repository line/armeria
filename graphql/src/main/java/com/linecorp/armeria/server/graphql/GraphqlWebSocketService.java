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

package com.linecorp.armeria.server.graphql;

import java.util.function.Consumer;
import java.util.function.Function;

import org.dataloader.DataLoaderRegistry;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServiceOptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketProtocolHandler;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.server.websocket.WebSocketServiceBuilder;
import com.linecorp.armeria.server.websocket.WebSocketServiceHandler;

final class GraphqlWebSocketService implements GraphqlService, WebSocketService, WebSocketServiceHandler {

    private static final String GRAPHQL_TRANSPORT_WS = "graphql-transport-ws";

    private final WebSocketService delegate;
    private final Function<? super ServiceRequestContext, ? extends DataLoaderRegistry>
            dataLoaderRegistryFunction;
    private final GraphqlExecutor graphqlExecutor;

    GraphqlWebSocketService(
            DefaultGraphqlService graphqlService,
            Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFunction,
            @Nullable Consumer<WebSocketServiceBuilder> webSocketServiceCustomizer) {
        final WebSocketServiceBuilder webSocketServiceBuilder =
                WebSocketService.builder(this)
                                .fallbackService(graphqlService)
                                .subprotocols(GRAPHQL_TRANSPORT_WS)
                                .aggregateContinuation(true);
        if (webSocketServiceCustomizer != null) {
            webSocketServiceCustomizer.accept(webSocketServiceBuilder);
        }
        delegate = webSocketServiceBuilder.build();
        graphqlExecutor = graphqlService;
        this.dataLoaderRegistryFunction = dataLoaderRegistryFunction;
    }

    @Override
    public WebSocket serve(ServiceRequestContext ctx, WebSocket in) throws Exception {
        return delegate.serve(ctx, in);
    }

    @Override
    public WebSocketProtocolHandler protocolHandler() {
        return delegate.protocolHandler();
    }

    @Override
    public WebSocket handle(ServiceRequestContext ctx, WebSocket in) {
        final WebSocketWriter outgoing = WebSocket.streaming();
        final GraphqlWSSubProtocol protocol =
                new GraphqlWSSubProtocol(ctx, graphqlExecutor, dataLoaderRegistryFunction);
        in.subscribe(new GraphqlWebSocketSubscriber(protocol, outgoing));
        return outgoing;
    }

    @Override
    public ServiceOptions options() {
        return delegate.options();
    }
}

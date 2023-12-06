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
 *
 */

package com.linecorp.armeria.client.kubernetes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketClientHandshakeException;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.fabric8.kubernetes.client.http.StandardHttpRequest;
import io.fabric8.kubernetes.client.http.StandardWebSocketBuilder;
import io.fabric8.kubernetes.client.http.WebSocket;
import io.fabric8.kubernetes.client.http.WebSocketResponse;
import io.fabric8.kubernetes.client.http.WebSocketUpgradeResponse;

final class ArmeriaWebSocketClient implements SafeCloseable {

    private final ArmeriaHttpClientBuilder armeriaHttpClientBuilder;
    @Nullable
    private WebSocketClient webSocketClient;

    ArmeriaWebSocketClient(ArmeriaHttpClientBuilder armeriaHttpClientBuilder) {
        this.armeriaHttpClientBuilder = armeriaHttpClientBuilder;
    }

    private WebSocketClient webSocketClient() {
        if (webSocketClient == null) {
            webSocketClient = WebSocketClient.builder()
                                             .factory(armeriaHttpClientBuilder.clientFactory(true))
                                             .build();
        }
        return webSocketClient;
    }

    CompletableFuture<WebSocketResponse> execute(StandardWebSocketBuilder webSocketRequest,
                                                 WebSocket.Listener listener) {
        final StandardHttpRequest request = webSocketRequest.asHttpRequest();
        HttpHeaders wsHeaders = HttpHeaders.of();
        if (!request.headers().isEmpty() || !Strings.isNullOrEmpty(webSocketRequest.getSubprotocol())) {
            final HttpHeadersBuilder headersBuilder = HttpHeaders.builder();
            request.headers().forEach(headersBuilder::add);

            if (!Strings.isNullOrEmpty(webSocketRequest.getSubprotocol())) {
                headersBuilder.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, webSocketRequest.getSubprotocol());
            }
            wsHeaders = headersBuilder.build();
        }

        RequestOptions requestOptions = RequestOptions.of();
        final Duration timeout = request.getTimeout();
        if (timeout != null && !Duration.ZERO.equals(timeout)) {
            requestOptions = RequestOptions.builder()
                                           .responseTimeout(timeout)
                                           .build();
        }

        final CompletableFuture<WebSocketSession> sessionFuture =
                webSocketClient().connect(request.uri().toString(), wsHeaders, requestOptions);

        return sessionFuture.handle((session, cause) -> {
            if (cause != null) {
                cause = Exceptions.peel(cause);
                if (cause instanceof WebSocketClientHandshakeException) {
                    final ResponseHeaders upgradeHeaders =
                            ((WebSocketClientHandshakeException) cause).headers();
                    return new WebSocketResponse(newUpgradeResponse(request, upgradeHeaders), cause);
                } else {
                    return Exceptions.throwUnsafely(cause);
                }
            }

            final ArmeriaWebSocket webSocket = new ArmeriaWebSocket(session.outbound(), listener);
            session.inbound().subscribe(webSocket, session.context().eventLoop());
            return new WebSocketResponse(newUpgradeResponse(request, session.responseHeaders()), webSocket);
        });
    }

    @Override
    public void close() {
        if (webSocketClient != null) {
            webSocketClient.options().factory().close();
        }
    }

    private static WebSocketUpgradeResponse newUpgradeResponse(StandardHttpRequest request,
                                                               ResponseHeaders upgradeHeaders) {
        return new WebSocketUpgradeResponse(request, upgradeHeaders.status().code(), toMap(upgradeHeaders));
    }

    // TODO(ikhoon): Consider adding `HttpHeaders.toMap()`.
    private static Map<String, List<String>> toMap(HttpHeaders headers) {
        final Map<String, List<String>> map = new HashMap<>();
        headers.forEach((name, value) -> {
            final String nameStr = name.toString();
            final List<String> values = map.computeIfAbsent(nameStr, k -> new ArrayList<>());
            values.add(value);
        });
        return Collections.unmodifiableMap(map);
    }
}

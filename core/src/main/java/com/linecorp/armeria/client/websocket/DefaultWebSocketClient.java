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

import static com.linecorp.armeria.internal.client.websocket.WebSocketClientUtil.UNDEFINED_WEBSOCKET_URI;
import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.generateSecWebSocketAccept;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.DefaultSplitHttpResponse;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameDecoder;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;
import com.linecorp.armeria.internal.common.websocket.WebSocketWrapper;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.internal.PlatformDependent;

final class DefaultWebSocketClient implements WebSocketClient {

    static final WebSocketClient DEFAULT = new DefaultWebSocketClient();

    private static final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(true);

    private final WebClient webClient;
    private final int maxFramePayloadLength;
    private final boolean allowMaskMismatch;
    private final List<String> subprotocols;
    private final String joinedSubprotocols;

    DefaultWebSocketClient() {
        webClient = WebClient.builder(UNDEFINED_WEBSOCKET_URI)
                             .responseTimeoutMillis(0)
                             .maxResponseLength(0)
                             .requestAutoAbortDelayMillis(5000)
                             .option(ClientOptions.ADD_ORIGIN_HEADER, true)
                             .build();
        maxFramePayloadLength = WebSocketClientBuilder.DEFAULT_MAX_FRAME_PAYLOAD_LENGTH;
        allowMaskMismatch = false;
        subprotocols = ImmutableList.of();
        joinedSubprotocols = "";
    }

    DefaultWebSocketClient(WebClient webClient, int maxFramePayloadLength, boolean allowMaskMismatch,
                           List<String> subprotocols) {
        this.webClient = webClient;
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.allowMaskMismatch = allowMaskMismatch;
        this.subprotocols = subprotocols;
        if (!subprotocols.isEmpty()) {
            joinedSubprotocols = Joiner.on(", ").join(subprotocols);
        } else {
            joinedSubprotocols = "";
        }
    }

    @Override
    public CompletableFuture<WebSocketSession> connect(String path) {
        requireNonNull(path, "path");
        final RequestHeadersBuilder builder;
        if (scheme().sessionProtocol().isExplicitHttp2()) {
            builder = RequestHeaders.builder(HttpMethod.CONNECT, path)
                                    .set(HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET.toString());
        } else {
            builder = RequestHeaders.builder(HttpMethod.GET, path)
                                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE.toString())
                                    .set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET.toString());
            final String secWebSocketKey = generateSecWebSocketKey();
            builder.set(HttpHeaderNames.SEC_WEBSOCKET_KEY, secWebSocketKey);
        }

        builder.set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        final List<String> protocols = ImmutableList.of();
        if (!protocols.isEmpty()) {
            builder.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, joinedSubprotocols);
        }

        final RequestHeaders requestHeaders = builder.build();

        final CompletableFuture<StreamMessage<HttpData>> outboundFuture = new CompletableFuture<>();
        final HttpRequest request = HttpRequest.of(requestHeaders, StreamMessage.of(outboundFuture));
        final HttpResponse response;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            response = webClient.execute(request);
            ctx = captor.get();
        }
        final SplitHttpResponse split =
                new DefaultSplitHttpResponse(response, ctx.eventLoop(), responseHeaders -> {
                    final SessionProtocol actualSessionProtocol = actualSessionProtocol(ctx);
                    if (actualSessionProtocol.isExplicitHttp1()) {
                        return true;
                    }
                    assert actualSessionProtocol.isExplicitHttp2();
                    return !responseHeaders.status().isInformational();
                });

        final CompletableFuture<WebSocketSession> result = new CompletableFuture<>();
        split.headers().handle((responseHeaders, cause) -> {
            if (cause != null) {
                fail(outboundFuture, response, result, cause);
                return null;
            }
            if (actualSessionProtocol(ctx).isExplicitHttp2()) {
                final HttpStatus status = responseHeaders.status();
                if (status != HttpStatus.OK) {
                    fail(outboundFuture, response, result, new WebSocketClientHandshakeException(
                            "invalid status: " + status + " (expected: " + HttpStatus.OK + ')',
                            responseHeaders));
                    return null;
                }
            } else {
                if (!isHttp1WebSocketResponse(responseHeaders)) {
                    fail(outboundFuture, response, result, new WebSocketClientHandshakeException(
                            "invalid response headers: " + responseHeaders, responseHeaders));
                    return null;
                }
                final String secWebSocketKey = requestHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_KEY);
                assert secWebSocketKey != null;
                final String secWebSocketAccept = responseHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT);
                if (secWebSocketAccept == null) {
                    fail(outboundFuture, response, result, new WebSocketClientHandshakeException(
                            HttpHeaderNames.SEC_WEBSOCKET_ACCEPT + " is null.", responseHeaders));
                    return null;
                }
                if (!secWebSocketAccept.equals(generateSecWebSocketAccept(secWebSocketKey))) {
                    fail(outboundFuture, response, result, new WebSocketClientHandshakeException(
                            "invalid " + HttpHeaderNames.SEC_WEBSOCKET_ACCEPT + " header: " +
                            secWebSocketAccept, responseHeaders));
                    return null;
                }
            }

            if (!subprotocols.isEmpty()) {
                final String responseSubprotocol = responseHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
                if (!subprotocols.contains(responseSubprotocol)) {
                    fail(outboundFuture, response, result, new WebSocketClientHandshakeException(
                            "invalid " + HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL + " header: " +
                            responseSubprotocol + " (expected: one of " + subprotocols + ')',
                            responseHeaders));
                    return null;
                }
            }

            final WebSocketFrameDecoder decoder =
                    new WebSocketFrameDecoder(ctx, maxFramePayloadLength, allowMaskMismatch, false);
            final WebSocketWrapper inbound = new WebSocketWrapper(split.body().decode(decoder));

            result.complete(new WebSocketSession(ctx, responseHeaders, inbound, outboundFuture, encoder));
            return null;
        });
        return result;
    }

    private static SessionProtocol actualSessionProtocol(ClientRequestContext ctx) {
        // This is always called after a ResponseHeaders is received which means
        // RequestLogProperty.SESSION is already set.
        return ctx.log().ensureAvailable(RequestLogProperty.SESSION).sessionProtocol();
    }

    private static void fail(CompletableFuture<StreamMessage<HttpData>> outboundFuture, HttpResponse response,
                             CompletableFuture<WebSocketSession> result, Throwable cause) {
        outboundFuture.completeExceptionally(cause);
        response.abort(cause);
        result.completeExceptionally(cause);
    }

    @VisibleForTesting
    static String generateSecWebSocketKey() {
        final byte[] bytes = new byte[16];
        PlatformDependent.threadLocalRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean isHttp1WebSocketResponse(ResponseHeaders responseHeaders) {
        return responseHeaders.status() == HttpStatus.SWITCHING_PROTOCOLS &&
               HttpHeaderValues.WEBSOCKET.toString().equals(responseHeaders.get(HttpHeaderNames.UPGRADE)) &&
               HttpHeaderValues.UPGRADE.toString().equals(responseHeaders.get(HttpHeaderNames.CONNECTION));
    }

    @Override
    public Scheme scheme() {
        return webClient.scheme();
    }

    @Override
    public EndpointGroup endpointGroup() {
        return webClient.endpointGroup();
    }

    @Override
    public String absolutePathRef() {
        return webClient.absolutePathRef();
    }

    @Override
    public URI uri() {
        return webClient.uri();
    }

    @Override
    public Class<?> clientType() {
        return webClient.clientType();
    }

    @Override
    public ClientOptions options() {
        return webClient.options();
    }

    @Override
    public WebClient unwrap() {
        return webClient;
    }
}

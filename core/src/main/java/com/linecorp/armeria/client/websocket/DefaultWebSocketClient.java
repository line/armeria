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

import static com.linecorp.armeria.internal.client.ClientUtil.UNDEFINED_URI;
import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.generateSecWebSocketAccept;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
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
import com.linecorp.armeria.common.stream.ByteStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.DefaultSplitHttpResponse;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;
import com.linecorp.armeria.internal.common.websocket.WebSocketWrapper;

import io.netty.handler.codec.http.HttpHeaderValues;

final class DefaultWebSocketClient implements WebSocketClient {

    static final WebSocketClient DEFAULT = WebSocketClient.of(UNDEFINED_URI);

    private static final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(true);

    private final WebClient webClient;
    private final int maxFramePayloadLength;
    private final boolean allowMaskMismatch;
    private final List<String> subprotocols;
    private final String joinedSubprotocols;
    private final boolean aggregateContinuation;

    DefaultWebSocketClient(WebClient webClient, int maxFramePayloadLength, boolean allowMaskMismatch,
                           List<String> subprotocols, boolean aggregateContinuation) {
        this.webClient = webClient;
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.allowMaskMismatch = allowMaskMismatch;
        this.subprotocols = subprotocols;
        if (!subprotocols.isEmpty()) {
            joinedSubprotocols = Joiner.on(", ").join(subprotocols);
        } else {
            joinedSubprotocols = "";
        }
        this.aggregateContinuation = aggregateContinuation;
    }

    @Override
    public CompletableFuture<WebSocketSession> connect(String path, HttpHeaders headers,
                                                       RequestOptions requestOptions) {
        requireNonNull(path, "path");
        final RequestHeaders requestHeaders = webSocketHeaders(path, headers);

        final CompletableFuture<StreamMessage<HttpData>> outboundFuture = new CompletableFuture<>();
        final HttpRequest request = HttpRequest.of(requestHeaders, StreamMessage.of(outboundFuture));
        final HttpResponse response;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            response = webClient.execute(request, requestOptions);
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
                fail(outboundFuture, split.body(), result, cause);
                return null;
            }
            if (!validateResponseHeaders(ctx, requestHeaders, responseHeaders, outboundFuture,
                                         split.body(), result)) {
                return null;
            }

            final WebSocketClientFrameDecoder decoder =
                    new WebSocketClientFrameDecoder(ctx, maxFramePayloadLength, allowMaskMismatch,
                                                    aggregateContinuation);
            final WebSocketWrapper inbound = new WebSocketWrapper(split.body().decode(decoder, ctx.alloc()));

            result.complete(new WebSocketSession(ctx, responseHeaders, inbound, outboundFuture, encoder));
            return null;
        });
        return result;
    }

    private RequestHeaders webSocketHeaders(String path, HttpHeaders headers) {
        final RequestHeadersBuilder builder = RequestHeaders.builder();
        if (!headers.isEmpty()) {
            headers.forEach((k, v) -> builder.add(k, v));
        }

        if (scheme().sessionProtocol().isExplicitHttp2()) {
            builder.method(HttpMethod.CONNECT)
                   .path(path)
                   .set(HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET.toString());
        } else {
            final String secWebSocketKey = generateSecWebSocketKey();
            builder.method(HttpMethod.GET)
                   .path(path)
                   .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE.toString())
                   .set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET.toString())
                   .set(HttpHeaderNames.SEC_WEBSOCKET_KEY, secWebSocketKey);
        }

        builder.set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        if (!builder.contains(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL) && !subprotocols.isEmpty()) {
            builder.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, joinedSubprotocols);
        }

        return builder.build();
    }

    private boolean validateResponseHeaders(
            ClientRequestContext ctx, RequestHeaders requestHeaders, ResponseHeaders responseHeaders,
            CompletableFuture<StreamMessage<HttpData>> outboundFuture, ByteStreamMessage responseBody,
            CompletableFuture<WebSocketSession> result) {
        if (actualSessionProtocol(ctx).isExplicitHttp2()) {
            final HttpStatus status = responseHeaders.status();
            if (status != HttpStatus.OK) {
                fail(outboundFuture, responseBody, result, new WebSocketClientHandshakeException(
                        "invalid status: " + status + " (expected: " + HttpStatus.OK + ')',
                        responseHeaders));
                return false;
            }
        } else {
            if (!isHttp1WebSocketResponse(responseHeaders)) {
                fail(outboundFuture, responseBody, result, new WebSocketClientHandshakeException(
                        "invalid response headers: " + responseHeaders, responseHeaders));
                return false;
            }
            final String secWebSocketKey = requestHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_KEY);
            assert secWebSocketKey != null;
            final String secWebSocketAccept = responseHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT);
            if (secWebSocketAccept == null) {
                fail(outboundFuture, responseBody, result, new WebSocketClientHandshakeException(
                        HttpHeaderNames.SEC_WEBSOCKET_ACCEPT + " is null.", responseHeaders));
                return false;
            }
            if (!secWebSocketAccept.equals(generateSecWebSocketAccept(secWebSocketKey))) {
                fail(outboundFuture, responseBody, result, new WebSocketClientHandshakeException(
                        "invalid " + HttpHeaderNames.SEC_WEBSOCKET_ACCEPT + " header: " +
                        secWebSocketAccept, responseHeaders));
                return false;
            }
        }

        if (!subprotocols.isEmpty()) {
            final String responseSubprotocol = responseHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
            // null is allowed if the server does not agree to any of the client's requested
            // subprotocols.
            // https://datatracker.ietf.org/doc/html/rfc6455#section-4.2.2

            if (responseSubprotocol != null && !subprotocols.contains(responseSubprotocol)) {
                fail(outboundFuture, responseBody, result, new WebSocketClientHandshakeException(
                        "invalid " + HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL + " header: " +
                        responseSubprotocol + " (expected: one of " + subprotocols + ')',
                        responseHeaders));
                return false;
            }
        }
        return true;
    }

    private static SessionProtocol actualSessionProtocol(ClientRequestContext ctx) {
        // This is always called after a ResponseHeaders is received which means
        // RequestLogProperty.SESSION is already set.
        return ctx.log().ensureAvailable(RequestLogProperty.SESSION).sessionProtocol();
    }

    private static void fail(CompletableFuture<StreamMessage<HttpData>> outboundFuture,
                             ByteStreamMessage responseBody,
                             CompletableFuture<WebSocketSession> result, Throwable cause) {
        outboundFuture.completeExceptionally(cause);
        responseBody.abort(cause);
        result.completeExceptionally(cause);
    }

    @VisibleForTesting
    static String generateSecWebSocketKey() {
        final byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean isHttp1WebSocketResponse(ResponseHeaders responseHeaders) {
        return responseHeaders.status() == HttpStatus.SWITCHING_PROTOCOLS &&
               HttpHeaderValues.WEBSOCKET.contentEqualsIgnoreCase(
                       responseHeaders.get(HttpHeaderNames.UPGRADE)) &&
               HttpHeaderValues.UPGRADE.contentEqualsIgnoreCase(
                       responseHeaders.get(HttpHeaderNames.CONNECTION));
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

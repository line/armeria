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
import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.isHttp1WebSocketUpgradeRequest;
import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.isHttp2WebSocketUpgradeRequest;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Base64;
import java.util.List;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

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
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
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
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
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

    DefaultWebSocketClient() {
        webClient = WebClient.builder(UNDEFINED_WEBSOCKET_URI)
                             .responseTimeoutMillis(0)
                             .maxResponseLength(0)
                             .requestAutoAbortDelayMillis(5000)
                             .option(ClientOptions.ADD_ORIGIN_HEADER, true)
                             .build();
        maxFramePayloadLength = WebSocketClientBuilder.DEFAULT_MAX_FRAME_PAYLOAD_LENGTH;
        allowMaskMismatch = false;
    }

    DefaultWebSocketClient(WebClient webClient, int maxFramePayloadLength, boolean allowMaskMismatch) {
        this.webClient = webClient;
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.allowMaskMismatch = allowMaskMismatch;
    }

    @Override
    public void connect(String path, Iterable<String> subprotocols, WebSocketClientHandler handler) {
        requireNonNull(path, "path");
        requireNonNull(subprotocols, "subprotocols");
        requireNonNull(handler, "handler");
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
        final List<String> protocols = ImmutableList.copyOf(subprotocols);
        if (!protocols.isEmpty()) {
            builder.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, Joiner.on(", ").join(protocols));
        }

        connect(builder.build(), handler);
    }

    @Override
    public void connect(RequestHeaders requestHeaders, WebSocketClientHandler handler) {
        requireNonNull(requestHeaders, "requestHeaders");
        requireNonNull(handler, "handler");
        validateHeaders(requestHeaders);
        final HttpRequestWriter requestWriter = HttpRequest.streaming(requestHeaders);
        final ClientRequestContext ctx;
        final HttpResponse response;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            response = webClient.execute(requestWriter);
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

        split.headers().handle((responseHeaders, cause) -> {
            if (cause != null) {
                fail(ctx, handler, requestWriter, response, cause);
                return null;
            }
            if (actualSessionProtocol(ctx).isExplicitHttp2()) {
                final HttpStatus status = responseHeaders.status();
                if (status != HttpStatus.OK) {
                    fail(ctx, handler, requestWriter, response, new WebSocketClientHandshakeException(
                            "invalid status: " + status + " (expected: " + HttpStatus.OK + ')',
                            responseHeaders));
                    return null;
                }
            } else {
                if (!isHttp1WebSocketResponse(responseHeaders)) {
                    fail(ctx, handler, requestWriter, response, new WebSocketClientHandshakeException(
                            "invalid headers: " + responseHeaders, responseHeaders));
                    return null;
                }
                final String secWebSocketKey = requestHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_KEY);
                assert secWebSocketKey != null; // We already validated the request headers.
                final String secWebSocketAccept = responseHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT);
                if (secWebSocketAccept == null) {
                    fail(ctx, handler, requestWriter, response, new WebSocketClientHandshakeException(
                            HttpHeaderNames.SEC_WEBSOCKET_ACCEPT + " is null.", responseHeaders));
                    return null;
                }
                if (!secWebSocketAccept.equals(generateSecWebSocketAccept(secWebSocketKey))) {
                    fail(ctx, handler, requestWriter, response, new WebSocketClientHandshakeException(
                            "invalid " + HttpHeaderNames.SEC_WEBSOCKET_ACCEPT + " header: " +
                            secWebSocketAccept, responseHeaders));
                    return null;
                }
            }
            final WebSocketFrameDecoder decoder =
                    new WebSocketFrameDecoder(ctx, maxFramePayloadLength, allowMaskMismatch, false);
            final WebSocketWrapper in = new WebSocketWrapper(split.body().decode(decoder));
            final WebSocket outbound = handler.handle(ctx, in);
            if (outbound == null) {
                final NullPointerException exception =
                        new NullPointerException("handler.handle() returned a null.");
                in.abort(exception);
                outbound.abort(exception);
                requestWriter.abort(exception);
                response.abort(exception);
                return null;
            }
            final StreamMessage<HttpData> src =
                    outbound.map(webSocketFrame -> HttpData.wrap(encoder.encode(ctx, webSocketFrame)));
            writeTo(src, requestWriter, ctx);
            return null;
        });
    }

    private static SessionProtocol actualSessionProtocol(ClientRequestContext ctx) {
        // This is always called after a ResponseHeaders is received which means
        // RequestLogProperty.SESSION is already set.
        return ctx.log().ensureAvailable(RequestLogProperty.SESSION).sessionProtocol();
    }

    private static void writeTo(StreamMessage<HttpData> src, StreamWriter<HttpObject> dst,
                                ClientRequestContext ctx) {
        // TODO(minwoox): Add this API to StreamMessage.
        src.subscribe(new Subscriber<HttpData>() {
            private Subscription s;

            @Override
            public void onSubscribe(Subscription s) {
                this.s = s;
                dst.whenConsumed().thenRun(() -> s.request(1));
            }

            @Override
            public void onNext(HttpData httpData) {
                dst.write(httpData);
                dst.whenConsumed().thenRun(() -> s.request(1));
            }

            @Override
            public void onError(Throwable t) {
                dst.close(t);
            }

            @Override
            public void onComplete() {
                dst.close();
            }
        }, ctx.eventLoop(), SubscriptionOption.values());
    }

    private void validateHeaders(RequestHeaders requestHeaders) {
        final SessionProtocol sessionProtocol = scheme().sessionProtocol();
        if (sessionProtocol.isExplicitHttp2()) {
            if (isHttp2WebSocketUpgradeRequest(requestHeaders)) {
                return;
            }
        } else if (sessionProtocol.isExplicitHttp1()) {
            if (isHttp1WebSocketUpgradeRequest(requestHeaders)) {
                return;
            }
        } else {
            if (isHttp2WebSocketUpgradeRequest(requestHeaders) ||
                isHttp1WebSocketUpgradeRequest(requestHeaders)) {
                return;
            }
        }
        throw new IllegalArgumentException("invalid WebSocket request headers: " + requestHeaders);
    }

    private static void fail(ClientRequestContext ctx, WebSocketClientHandler handler,
                             HttpRequestWriter requestWriter, HttpResponse response, Throwable cause) {
        final WebSocketWriter in = WebSocket.streaming();
        in.abort(cause);
        final WebSocket out = handler.handle(ctx, in);
        out.abort(cause);
        requestWriter.abort(cause);
        response.abort(cause);
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

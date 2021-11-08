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
package com.linecorp.armeria.server.websocket;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.isHttp1WebSocketUpgradeRequest;
import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.isHttp2WebSocketUpgradeRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import com.google.common.base.Splitter;
import com.google.common.hash.Hashing;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketDecoderConfig;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.internal.common.websocket.WebSocketCloseHandler;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameDecoder;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

/**
 * An {@link HttpService} that supports <a href="https://datatracker.ietf.org/doc/html/rfc6455">
 * The WebSocket Protocol</a>.
 */
@UnstableApi
public final class WebSocketService extends AbstractHttpService {

    private static final String WEBSOCKET_13_ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final String SUB_PROTOCOL_WILDCARD = "*";

    private static final AggregatedHttpResponse missingHttp1WebSocketUpgradeHeader =
            AggregatedHttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                      HttpData.ofUtf8("The upgrade header must contain:\n" +
                                                      "  Upgrade: websocket\n" +
                                                      "  Connection: Upgrade"));

    private static final AggregatedHttpResponse missingHttp2WebSocketUpgradeHeader =
            AggregatedHttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                      HttpData.ofUtf8("The upgrade header must contain:\n" +
                                                      "  :protocol = websocket"));

    private static final AggregatedHttpResponse missingWebSocketKeyHeader =
            AggregatedHttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                      HttpData.ofUtf8("The upgrade header must contain Sec-WebSocket-Key."));

    private static final ResponseHeaders unsupportedWebSocketVersion =
            ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
                           .add(HttpHeaderNames.SEC_WEBSOCKET_VERSION, WebSocketVersion.V13.toHttpHeaderValue())
                           .build();

    private static final Splitter commaSplitter = Splitter.on(',').trimResults().omitEmptyStrings();

    private static final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(false);

    /**
     * Returns a new {@link WebSocketService} with the {@link WebSocketHandler}.
     */
    public static WebSocketService of(WebSocketHandler handler) {
        return new WebSocketServiceBuilder(handler).build();
    }

    /**
     * Returns a new {@link WebSocketServiceBuilder} with the {@link WebSocketHandler}.
     */
    public static WebSocketServiceBuilder builder(WebSocketHandler handler) {
        return new WebSocketServiceBuilder(handler);
    }

    private final WebSocketHandler handler;
    private final WebSocketDecoderConfig decoderConfig;
    private final Set<String> subprotocols;
    private final Set<String> allowedOrigins;
    private final long closeTimeoutMillis;

    WebSocketService(WebSocketHandler handler, WebSocketDecoderConfig decoderConfig,
                     Set<String> subprotocols, Set<String> allowedOrigins, long closeTimeoutMillis) {
        this.handler = handler;
        this.decoderConfig = decoderConfig;
        this.subprotocols = subprotocols;
        this.allowedOrigins = allowedOrigins;
        this.closeTimeoutMillis = closeTimeoutMillis;
    }

    /**
     * Handles the HTTP/1.1 web socket handshake described in
     * <a href="https://datatracker.ietf.org/doc/html/rfc6455">The WebSocket Protocol</a>.
     * These are examples of a request and the corresponding response:
     *
     * <p>Request:
     * <pre>
     * GET /chat HTTP/1.1
     * Host: server.example.com
     * Upgrade: websocket
     * Connection: Upgrade
     * Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
     * Origin: http://example.com
     * Sec-WebSocket-Protocol: chat, superchat
     * Sec-WebSocket-Version: 13
     * </pre>
     *
     * <p>Response:
     * <pre>
     * HTTP/1.1 101 Switching Protocols
     * Upgrade: websocket
     * Connection: Upgrade
     * Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
     * Sec-WebSocket-Protocol: chat
     * </pre>
     */
    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (!ctx.sessionProtocol().isHttp1()) {
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }
        final RequestHeaders headers = req.headers();
        if (!isHttp1WebSocketUpgradeRequest(headers)) {
            return missingHttp1WebSocketUpgradeHeader.toHttpResponse();
        }

        if (!allowedOrigins.isEmpty()) {
            final String origin = headers.get(HttpHeaderNames.ORIGIN);
            if (isNullOrEmpty(origin) || !allowedOrigins.contains(origin)) {
                return HttpResponse.of(HttpStatus.FORBIDDEN);
            }
        }

        // Currently we only support v13.
        if (!WebSocketVersion.V13.toHttpHeaderValue().equalsIgnoreCase(
                headers.get(HttpHeaderNames.SEC_WEBSOCKET_VERSION))) {
            return HttpResponse.of(unsupportedWebSocketVersion);
        }

        final String webSocketKey = headers.get(HttpHeaderNames.SEC_WEBSOCKET_KEY);
        if (isNullOrEmpty(webSocketKey)) {
            return missingWebSocketKeyHeader.toHttpResponse();
        }

        final String accept = generateSecWebSocketAccept(webSocketKey);
        final ResponseHeadersBuilder responseHeadersBuilder =
                ResponseHeaders.builder(HttpStatus.SWITCHING_PROTOCOLS)
                               .add(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET.toString())
                               .add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE.toString())
                               .add(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, accept);
        maybeAddSubprotocol(headers, responseHeadersBuilder);
        return handleUpgradeRequest(ctx, req, responseHeadersBuilder.build());
    }

    private void maybeAddSubprotocol(RequestHeaders headers,
                                     ResponseHeadersBuilder responseHeadersBuilder) {
        final String subprotocols = headers.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (isNullOrEmpty(subprotocols)) {
            return;
        }
        commaSplitter.splitToStream(subprotocols)
                     .filter(sub -> SUB_PROTOCOL_WILDCARD.equals(sub) ||
                                    this.subprotocols.contains(sub))
                     .findFirst().ifPresent(selectedSubprotocol -> responseHeadersBuilder.add(
                             HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, selectedSubprotocol));
    }

    // Generate Sec-WebSocket-Accept using Sec-WebSocket-Key.
    // See https://datatracker.ietf.org/doc/html/rfc6455#section-11.3.3
    private static String generateSecWebSocketAccept(String webSocketKey) {
        final String acceptSeed = webSocketKey + WEBSOCKET_13_ACCEPT_GUID;
        final byte[] sha1 = Hashing.sha1().hashBytes(acceptSeed.getBytes(StandardCharsets.US_ASCII)).asBytes();
        return Base64.getEncoder().encodeToString(sha1);
    }

    private HttpResponse handleUpgradeRequest(ServiceRequestContext ctx, HttpRequest req,
                                              ResponseHeaders responseHeaders) {
        if (ctx.requestTimeoutMillis() < closeTimeoutMillis) {
            ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, closeTimeoutMillis);
        }

        final HttpResponseWriter responseWriter = HttpResponse.streaming();
        responseWriter.write(responseHeaders);
        responseWriter.whenConsumed().thenRun(() -> {
            final WebSocketCloseHandler webSocketCloseHandler =
                    new WebSocketCloseHandler(ctx, responseWriter, closeTimeoutMillis);
            final WebSocketFrameDecoder decoder = new WebSocketFrameDecoder(
                    ctx, decoderConfig, responseWriter, encoder, webSocketCloseHandler, true);
            final StreamMessage<WebSocketFrame> inboundFrames = req.decode(decoder, ctx.alloc());
            try {
                final WebSocket outboundFrames = handler.handle(ctx, new WebSocketWrapper(inboundFrames));
                outboundFrames.subscribe(new WebSocketResponseSubscriber(ctx, responseWriter,
                                                                         encoder, webSocketCloseHandler),
                                         ctx.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);
            } catch (Throwable cause) {
                req.abort(cause);
                responseWriter.close(cause);
            }
        });
        return responseWriter;
    }

    /**
     * Handles the HTTP/2 web socket handshake described in
     * <a href="https://datatracker.ietf.org/doc/html/rfc8441">Bootstrapping WebSockets with HTTP/2</a>.
     * These are examples of a request and the corresponding response:
     *
     * <p>Request:
     * <pre>
     * HEADERS + END_HEADERS
     * :method = CONNECT
     * :protocol = websocket
     * :scheme = https
     * :path = /chat
     * :authority = server.example.com
     * sec-websocket-protocol = chat, superchat
     * sec-websocket-extensions = permessage-deflate
     * sec-websocket-version = 13
     * origin = http://www.example.com
     * </pre>
     *
     * <p>Response:
     * <pre>
     * HEADERS + END_HEADERS
     * :status = 200
     * sec-websocket-protocol = chat
     * </pre>
     */
    @Override
    protected HttpResponse doConnect(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (!ctx.sessionProtocol().isHttp2()) {
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }
        final RequestHeaders headers = req.headers();
        if (!isHttp2WebSocketUpgradeRequest(headers)) {
            return missingHttp2WebSocketUpgradeHeader.toHttpResponse();
        }
        // Currently we only support v13.
        if (!WebSocketVersion.V13.toHttpHeaderValue().equalsIgnoreCase(
                headers.get(HttpHeaderNames.SEC_WEBSOCKET_VERSION))) {
            return HttpResponse.of(unsupportedWebSocketVersion);
        }

        final ResponseHeadersBuilder responseHeadersBuilder = ResponseHeaders.builder(HttpStatus.OK);
        maybeAddSubprotocol(headers, responseHeadersBuilder);
        return handleUpgradeRequest(ctx, req, responseHeadersBuilder.build());
    }
}

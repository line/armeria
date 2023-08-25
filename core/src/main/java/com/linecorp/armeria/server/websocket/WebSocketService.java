/*
 * Copyright 2022 LINE Corporation
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

import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.generateSecWebSocketAccept;
import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.isHttp1WebSocketUpgradeRequest;
import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.isHttp2WebSocketUpgradeRequest;
import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.newCloseWebSocketFrame;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;
import com.google.common.net.HostAndPort;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;
import com.linecorp.armeria.internal.common.websocket.WebSocketWrapper;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

/**
 * An {@link HttpService} that supports <a href="https://datatracker.ietf.org/doc/html/rfc6455">
 * The WebSocket Protocol</a>.
 * This service has a few different default values for {@link ServiceConfig} from a normal {@link HttpService}
 * because of the nature of WebSocket. See {@link WebSocketServiceBuilder} for more information.
 */
@UnstableApi
public final class WebSocketService extends AbstractHttpService {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketService.class);

    private static final String SUB_PROTOCOL_WILDCARD = "*";

    private static final ResponseHeaders UNSUPPORTED_WEB_SOCKET_VERSION =
            ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
                           .add(HttpHeaderNames.SEC_WEBSOCKET_VERSION, WebSocketVersion.V13.toHttpHeaderValue())
                           .contentType(MediaType.PLAIN_TEXT_UTF_8)
                           .build();

    private static final Splitter commaSplitter = Splitter.on(',').trimResults().omitEmptyStrings();

    // Server-side encoder do not mask the payloads.
    private static final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(false);

    /**
     * Returns a new {@link WebSocketService} with the {@link WebSocketServiceHandler}.
     */
    public static WebSocketService of(WebSocketServiceHandler handler) {
        return new WebSocketServiceBuilder(handler).build();
    }

    /**
     * Returns a new {@link WebSocketServiceBuilder} with the {@link WebSocketServiceHandler}.
     */
    public static WebSocketServiceBuilder builder(WebSocketServiceHandler handler) {
        return new WebSocketServiceBuilder(handler);
    }

    private final WebSocketServiceHandler handler;
    private final int maxFramePayloadLength;
    private final boolean allowMaskMismatch;
    private final Set<String> subprotocols;
    private final Set<String> allowedOrigins;
    private final boolean allowAnyOrigin;

    WebSocketService(WebSocketServiceHandler handler, int maxFramePayloadLength, boolean allowMaskMismatch,
                     Set<String> subprotocols, Set<String> allowedOrigins, boolean allowAnyOrigin) {
        this.handler = handler;
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.allowMaskMismatch = allowMaskMismatch;
        this.subprotocols = subprotocols;
        this.allowedOrigins = allowedOrigins;
        this.allowAnyOrigin = allowAnyOrigin;
    }

    /**
     * Handles the HTTP/1.1 web socket handshake described in
     * <a href="https://datatracker.ietf.org/doc/html/rfc6455">The WebSocket Protocol</a>.
     * These are examples of a request and its corresponding response:
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
        if (!ctx.sessionProtocol().isExplicitHttp1()) {
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }
        final RequestHeaders headers = req.headers();
        if (!isHttp1WebSocketUpgradeRequest(headers)) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "The upgrade header must contain:\n" +
                                   "  Upgrade: websocket\n" +
                                   "  Connection: Upgrade");
        }

        HttpResponse invalidResponse = checkOrigin(ctx, headers);
        if (invalidResponse != null) {
            return invalidResponse;
        }

        invalidResponse = checkVersion(headers);
        if (invalidResponse != null) {
            return invalidResponse;
        }

        final String webSocketKey = headers.get(HttpHeaderNames.SEC_WEBSOCKET_KEY, "");
        if (webSocketKey.isEmpty()) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "missing Sec-WebSocket-Key header");
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
        final String subprotocols = headers.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "");
        if (subprotocols.isEmpty()) {
            return;
        }
        commaSplitter.splitToStream(subprotocols)
                     .filter(sub -> SUB_PROTOCOL_WILDCARD.equals(sub) ||
                                    this.subprotocols.contains(sub))
                     .findFirst().ifPresent(selectedSubprotocol -> responseHeadersBuilder.add(
                             HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, selectedSubprotocol));
    }

    private HttpResponse handleUpgradeRequest(ServiceRequestContext ctx, HttpRequest req,
                                              ResponseHeaders responseHeaders) {
        final WebSocketServiceFrameDecoder decoder =
                new WebSocketServiceFrameDecoder(ctx, maxFramePayloadLength, allowMaskMismatch);
        final StreamMessage<WebSocketFrame> inboundFrames = req.decode(decoder, ctx.alloc());
        final WebSocket outboundFrames = handler.handle(ctx, new WebSocketWrapper(inboundFrames));
        decoder.setOutboundWebSocket(outboundFrames);
        return HttpResponse.of(
                responseHeaders, outboundFrames.recoverAndResume(cause -> {
                                                   if (cause instanceof ClosedStreamException) {
                                                       return StreamMessage.aborted(cause);
                                                   }
                                                   ctx.logBuilder().responseCause(cause);
                                                   return StreamMessage.of(newCloseWebSocketFrame(cause));
                                               })
                                               .map(frame -> HttpData.wrap(encoder.encode(ctx, frame))));
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
        if (!ctx.sessionProtocol().isExplicitHttp2()) {
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }
        final RequestHeaders headers = req.headers();
        if (!isHttp2WebSocketUpgradeRequest(headers)) {
            logger.trace("RequestHeaders does not contain headers for WebSocket upgrade. headers: {}", headers);
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "The upgrade header must contain:\n" +
                                   "  :protocol = websocket");
        }

        HttpResponse invalidResponse = checkOrigin(ctx, headers);
        if (invalidResponse != null) {
            return invalidResponse;
        }

        invalidResponse = checkVersion(headers);
        if (invalidResponse != null) {
            return invalidResponse;
        }

        // As described in https://datatracker.ietf.org/doc/html/rfc8441#section-5,
        // HTTP/2 does not use Sec-WebSocket-Key and Sec-WebSocket-Accept headers.

        final ResponseHeadersBuilder responseHeadersBuilder = ResponseHeaders.builder(HttpStatus.OK);
        maybeAddSubprotocol(headers, responseHeadersBuilder);
        return handleUpgradeRequest(ctx, req, responseHeadersBuilder.build());
    }

    @Nullable
    private HttpResponse checkOrigin(ServiceRequestContext ctx, RequestHeaders headers) {
        if (allowAnyOrigin) {
            return null;
        }
        final String origin = headers.get(HttpHeaderNames.ORIGIN, "");
        if (origin.isEmpty()) {
            return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT_UTF_8,
                                   "missing the origin header");
        }

        if (allowedOrigins.isEmpty()) {
            // Only the same-origin is allowed.
            if (!isSameOrigin(ctx, headers, origin)) {
                return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT_UTF_8,
                                       "not allowed origin: " + origin);
            }
            return null;
        }
        if (!allowedOrigins.contains(origin)) {
            return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT_UTF_8,
                                   "not allowed origin: " + origin + ", allowed: " + allowedOrigins);
        }
        return null;
    }

    private static boolean isSameOrigin(ServiceRequestContext ctx, RequestHeaders headers, String origin) {
        final int schemeDelimiter = origin.indexOf("://");
        if (schemeDelimiter < 0) {
            return false;
        }

        final String scheme = origin.substring(0, schemeDelimiter);
        final SessionProtocol originSessionProtocol = SessionProtocol.find(scheme);
        if (originSessionProtocol == null) {
            return false;
        }

        if ((ctx.sessionProtocol().isHttp() && originSessionProtocol.isHttp()) ||
            (ctx.sessionProtocol().isHttps() && originSessionProtocol.isHttps())) {
            // The same scheme.
        } else {
            return false;
        }

        final String authority = headers.authority();
        assert authority != null;
        final HostAndPort authorityHostAndPort = HostAndPort.fromString(authority);
        final String authorityHost = authorityHostAndPort.getHost();
        final int authorityPort = authorityHostAndPort.getPortOrDefault(ctx.sessionProtocol().defaultPort());

        final HostAndPort originHostAndPort = HostAndPort.fromString(origin.substring(schemeDelimiter + 3));
        final String originHost = originHostAndPort.getHost();
        final int originPort = originHostAndPort.getPortOrDefault(originSessionProtocol.defaultPort());

        return authorityPort == originPort && authorityHost.equals(originHost);
    }

    @Nullable
    private static HttpResponse checkVersion(RequestHeaders headers) {
        // Currently we only support v13.
        final String version = headers.get(HttpHeaderNames.SEC_WEBSOCKET_VERSION);
        if (!WebSocketVersion.V13.toHttpHeaderValue().equalsIgnoreCase(version)) {
            return HttpResponse.of(UNSUPPORTED_WEB_SOCKET_VERSION,
                                   HttpData.ofUtf8("Only 13 version is supported."));
        }
        return null;
    }
}

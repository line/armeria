/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.isHttp1WebSocketUpgradeResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;
import com.linecorp.armeria.internal.common.util.HttpTimestampSupplier;
import com.linecorp.armeria.server.websocket.WebSocketService;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

final class ServerHttp1ObjectEncoder extends Http1ObjectEncoder implements ServerHttpObjectEncoder {

    private static final Logger logger = LoggerFactory.getLogger(ServerHttp1ObjectEncoder.class);

    private final KeepAliveHandler keepAliveHandler;
    private final boolean enableServerHeader;
    private final boolean enableDateHeader;
    private final Http1HeaderNaming http1HeaderNaming;
    private final WebSocketUpgradeContext webSocketUpgradeContext;

    private boolean shouldSendConnectionCloseHeader;
    private boolean sentConnectionCloseHeader;

    private int lastResponseHeadersId;

    ServerHttp1ObjectEncoder(Channel ch, SessionProtocol protocol, KeepAliveHandler keepAliveHandler,
                             boolean hasWebSocketService, boolean enableDateHeader,
                             boolean enableServerHeader, Http1HeaderNaming http1HeaderNaming) {
        super(ch, protocol);
        assert keepAliveHandler instanceof Http1ServerKeepAliveHandler ||
               keepAliveHandler instanceof NoopKeepAliveHandler;
        this.keepAliveHandler = keepAliveHandler;
        webSocketUpgradeContext = hasWebSocketService ? new DefaultWebSocketUpgradeContext()
                                                      : WebSocketUpgradeContext.noop();
        this.enableServerHeader = enableServerHeader;
        this.enableDateHeader = enableDateHeader;
        this.http1HeaderNaming = http1HeaderNaming;
    }

    @Override
    public KeepAliveHandler keepAliveHandler() {
        return keepAliveHandler;
    }

    WebSocketUpgradeContext webSocketUpgradeContext() {
        return webSocketUpgradeContext;
    }

    void initiateConnectionShutdown() {
        shouldSendConnectionCloseHeader = true;
    }

    @Override
    public ChannelFuture doWriteHeaders(int id, int streamId, ResponseHeaders headers, boolean endStream,
                                        boolean isTrailersEmpty) {
        if (!isWritable(id)) {
            return newClosedSessionFuture();
        }

        if (webSocketUpgradeContext.lastWebSocketUpgradeRequestId() == id) {
            if (isHttp1WebSocketUpgradeResponse(headers)) {
                webSocketUpgradeContext.setWebSocketSessionEstablished(true);
                logger.trace("WebSocket session is established. id: {}, response headers: {}", id, headers);
                return handleWebSocketUpgradeResponse(id, headers);
            }
            logger.trace("Failed to establish a WebSocket session. id: {}, response headers: {}", id, headers);
            webSocketUpgradeContext.setWebSocketSessionEstablished(false);
        }

        final HttpResponse converted = convertHeaders(headers, endStream, isTrailersEmpty);
        if (headers.status().isInformational()) {
            return write(id, converted, false);
        }
        lastResponseHeadersId = id;

        if (shouldSendConnectionCloseHeader || keepAliveHandler.needToCloseConnection()) {
            converted.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            sentConnectionCloseHeader = true;
        }
        return writeNonInformationalHeaders(id, converted, endStream, channel().newPromise());
    }

    private ChannelFuture handleWebSocketUpgradeResponse(int id, ResponseHeaders headers) {
        final HttpResponse res = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS, false);
        convertHeaders(headers, res.headers(), false /* To remove Content-Length header */);
        lastResponseHeadersId = id;
        return write(id, res, false);
    }

    private HttpResponse convertHeaders(ResponseHeaders headers, boolean endStream, boolean isTrailersEmpty) {
        final int statusCode = headers.status().code();
        final HttpResponseStatus nettyStatus = HttpResponseStatus.valueOf(statusCode);

        if (headers.status().isInformational()) {
            final HttpResponse res = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, nettyStatus, Unpooled.EMPTY_BUFFER, false);
            ArmeriaHttpUtil.toNettyHttp1ServerHeaders(headers, res.headers(), http1HeaderNaming);
            return res;
        }

        if (!endStream) {
            final HttpResponse res = new DefaultHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, false);
            convertHeaders(headers, res.headers(), isTrailersEmpty);
            maybeSetTransferEncoding(res, statusCode);
            return res;
        }

        final HttpResponse res =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, Unpooled.EMPTY_BUFFER, false);
        final io.netty.handler.codec.http.HttpHeaders outHeaders = res.headers();
        convertHeaders(headers, outHeaders, isTrailersEmpty);

        if (HttpStatus.isContentAlwaysEmpty(statusCode)) {
            maybeRemoveContentLength(statusCode, outHeaders);
        } else if (!headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
            // NB: Set the 'content-length' only when not set rather than always setting to 0.
            //     It's because a response to a HEAD request can have empty content while having
            //     non-zero 'content-length' header.
            //     However, this also opens the possibility of sending a non-zero 'content-length'
            //     header even when it really has to be zero. e.g. a response to a non-HEAD request
            outHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        }
        return res;
    }

    private void convertHeaders(HttpHeaders inHeaders, io.netty.handler.codec.http.HttpHeaders outHeaders,
                                boolean isTrailersEmpty) {
        ArmeriaHttpUtil.toNettyHttp1ServerHeaders(inHeaders, outHeaders, http1HeaderNaming);

        if (!isTrailersEmpty && outHeaders.contains(HttpHeaderNames.CONTENT_LENGTH)) {
            // We don't apply chunked encoding when the content-length header is set, which would
            // prevent the trailers from being sent so we go ahead and remove content-length to
            // force chunked encoding.
            outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }

        if (enableServerHeader && !outHeaders.contains(HttpHeaderNames.SERVER)) {
            outHeaders.add(HttpHeaderNames.SERVER, ArmeriaHttpUtil.SERVER_HEADER);
        }

        if (enableDateHeader && !outHeaders.contains(HttpHeaderNames.DATE)) {
            outHeaders.add(HttpHeaderNames.DATE, HttpTimestampSupplier.currentTime());
        }
    }

    private static void maybeRemoveContentLength(int statusCode,
                                                 io.netty.handler.codec.http.HttpHeaders outHeaders) {
        if (statusCode == 304) {
            // 304 response can have the "content-length" header when it is a response to a conditional
            // GET request. See https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.2
        } else {
            outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }
    }

    private static void maybeSetTransferEncoding(HttpMessage out, int statusCode) {
        final io.netty.handler.codec.http.HttpHeaders outHeaders = out.headers();
        if (HttpStatus.isContentAlwaysEmpty(statusCode)) {
            maybeRemoveContentLength(statusCode, outHeaders);
        } else {
            final long contentLength = HttpUtil.getContentLength(out, -1L);
            if (contentLength < 0) {
                // Use chunked encoding.
                outHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
            }
        }
    }

    @Override
    protected void convertTrailers(HttpHeaders inputHeaders,
                                   io.netty.handler.codec.http.HttpHeaders outputHeaders) {
        ArmeriaHttpUtil.toNettyHttp1ServerTrailers(inputHeaders, outputHeaders, http1HeaderNaming);
    }

    @Override
    protected boolean isPing(int id) {
        return false;
    }

    boolean isSentConnectionCloseHeader() {
        return sentConnectionCloseHeader;
    }

    @Override
    public boolean isResponseHeadersSent(int id, int streamId) {
        return id <= lastResponseHeadersId;
    }

    @Override
    public ChannelFuture writeErrorResponse(int id, int streamId,
                                            ServiceConfig serviceConfig,
                                            RequestHeaders headers,
                                            HttpStatus status,
                                            @Nullable String message,
                                            @Nullable Throwable cause) {

        // Destroy keepAlive handler before writing headers so that ServerHttp1ObjectEncoder sets
        // "Connection: close" to the response headers
        keepAliveHandler().destroy();

        final ChannelFuture future = ServerHttpObjectEncoder.super.writeErrorResponse(
                id, streamId, serviceConfig, headers, status, message, cause);
        // Update the closed ID to prevent the HttpResponseSubscriber from
        // writing additional headers or messages.
        updateClosedId(id);

        return future.addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * The context that has WebSocket upgrade and session information.
     */
    interface WebSocketUpgradeContext {

        /**
         * A noop context. This is used when the server doesn't have any {@link WebSocketService}.
         */
        static WebSocketUpgradeContext noop() {
            return NoopWebSocketUpgradeContext.INSTANCE;
        }

        /**
         * Sets the ID of the request that lastly tries to establish a WebSocket session.
         * The ID will be used to find the corresponding response. If the response contains the proper
         * WebSocket upgrade response headers, the WebSocket session will be established.
         */
        void setLastWebSocketUpgradeRequestId(int id);

        /**
         * Returns the ID of the request that lastly tries to establish a WebSocket session.
         */
        int lastWebSocketUpgradeRequestId();

        /**
         * Sets whether a WebSocket session is established. This will also reset the last request ID to -1 if
         * {@code success} is {@code false}.
         */
        void setWebSocketSessionEstablished(boolean success);

        /**
         * Tells whether WebSocket session is established.
         */
        boolean webSocketSessionEstablished();

        /**
         * Sets the listener that this context notifies when a WebSocket session is established or failed.
         * The listener is {@link Http1RequestDecoder}.
         * When the upgrade fails, the {@link Http1RequestDecoder} closes the request and removes
         * the reference of the request.
         */
        void setWebSocketUpgradeListener(WebSocketUpgradeListener listener);
    }

    @FunctionalInterface
    interface WebSocketUpgradeListener {
        void upgraded(boolean success);
    }

    static final class DefaultWebSocketUpgradeContext implements WebSocketUpgradeContext {

        private int lastWebSocketUpgradeRequestId = -1;

        private boolean webSocketEstablished;
        @SuppressWarnings("NotNullFieldNotInitialized")
        private WebSocketUpgradeListener listener;

        @Override
        public void setLastWebSocketUpgradeRequestId(int id) {
            lastWebSocketUpgradeRequestId = id;
        }

        @Override
        public int lastWebSocketUpgradeRequestId() {
            return lastWebSocketUpgradeRequestId;
        }

        @Override
        public void setWebSocketSessionEstablished(boolean success) {
            webSocketEstablished = success;
            if (!success) {
                lastWebSocketUpgradeRequestId = -1; // reset
            }
            listener.upgraded(success);
        }

        @Override
        public boolean webSocketSessionEstablished() {
            return webSocketEstablished;
        }

        @Override
        public void setWebSocketUpgradeListener(WebSocketUpgradeListener listener) {
            this.listener = listener;
        }
    }

    private enum NoopWebSocketUpgradeContext implements WebSocketUpgradeContext {

        INSTANCE;

        @Override
        public void setLastWebSocketUpgradeRequestId(int id) {}

        @Override
        public int lastWebSocketUpgradeRequestId() {
            return -1;
        }

        @Override
        public void setWebSocketSessionEstablished(boolean success) {}

        @Override
        public boolean webSocketSessionEstablished() {
            return false;
        }

        @Override
        public void setWebSocketUpgradeListener(WebSocketUpgradeListener listener) {}
    }
}

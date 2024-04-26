/*
 * Copyright 2016 LINE Corporation
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

import static com.linecorp.armeria.server.HttpServerPipelineConfigurator.SCHEME_HTTP;
import static com.linecorp.armeria.server.ServiceRouteUtil.newRoutingContext;
import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http2GoAwayHandler;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.KeepAliveHandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2EventAdapter;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.AsciiString;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;

final class Http2RequestDecoder extends Http2EventAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Http2RequestDecoder.class);

    private static final ResponseHeaders CONTINUE_RESPONSE = ResponseHeaders.of(HttpStatus.CONTINUE);

    private final ServerConfig cfg;
    private final Channel channel;
    private final AsciiString scheme;
    @Nullable
    private ServerHttp2ObjectEncoder encoder;

    private final InboundTrafficController inboundTrafficController;
    private final KeepAliveHandler keepAliveHandler;
    private final Http2GoAwayHandler goAwayHandler;
    private final IntObjectMap<@Nullable DecodedHttpRequest> requests = new IntObjectHashMap<>();
    private int nextId;

    Http2RequestDecoder(ServerConfig cfg, Channel channel,
                        AsciiString scheme, KeepAliveHandler keepAliveHandler) {
        this.cfg = cfg;
        this.channel = channel;
        this.scheme = scheme;
        inboundTrafficController =
                InboundTrafficController.ofHttp2(channel, cfg.http2InitialConnectionWindowSize());
        this.keepAliveHandler = keepAliveHandler;
        goAwayHandler = new Http2GoAwayHandler();
    }

    Http2GoAwayHandler goAwayHandler() {
        return goAwayHandler;
    }

    void initEncoder(ServerHttp2ObjectEncoder encoder) {
        if (this.encoder == null) {
            this.encoder = encoder;
        }
    }

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
        ctx.fireChannelRead(settings);
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers nettyHeaders, int padding,
                              boolean endOfStream) throws Http2Exception {
        keepAliveChannelRead(true);
        DecodedHttpRequest req = requests.get(streamId);
        if (req == null) {
            assert encoder != null;

            // Validate the method.
            final CharSequence methodText = nettyHeaders.method();
            final HttpMethod method;
            if (methodText != null) {
                method = HttpMethod.tryParse(methodText.toString());
            } else {
                method = null;
            }
            if (method == null) {
                final String message = methodText == null ? "Missing method" : "Invalid method: " + methodText;
                writeErrorResponse(streamId, null, HttpStatus.BAD_REQUEST, message, null);
                return;
            }

            // Parse and normalize the request path.
            final String path = nettyHeaders.path().toString();
            final RequestTarget reqTarget = RequestTarget.forServer(path);
            if (reqTarget == null) {
                writeInvalidRequestPathResponse(streamId, null);
                return;
            }

            // Handle `expect: 100-continue` first to give `handle100Continue()` a chance to remove
            // the `expect` header before converting the Netty HttpHeaders into Armeria RequestHeaders.
            // This is because removing a header from RequestHeaders is more expensive due to its
            // immutability.
            final boolean hasInvalidExpectHeader = !handle100Continue(streamId, nettyHeaders, method);

            // Convert the Netty Http2Headers into Armeria RequestHeaders.
            final RequestHeaders headers =
                    ArmeriaHttpUtil.toArmeriaRequestHeaders(ctx, nettyHeaders, endOfStream,
                                                            scheme.toString(), cfg, reqTarget);

            // Reject a request with an unsupported method.
            switch (method) {
                case CONNECT:
                    // Accept a CONNECT request only when it has a :protocol header, as defined in:
                    // https://datatracker.ietf.org/doc/html/rfc8441#section-4
                    if (!nettyHeaders.contains(HttpHeaderNames.PROTOCOL)) {
                        writeUnsupportedMethodResponse(streamId, headers);
                        return;
                    }
                    break;
                case UNKNOWN:
                    writeUnsupportedMethodResponse(streamId, headers);
                    return;
            }

            // Do not accept the request path '*' for a non-OPTIONS request.
            if (method != HttpMethod.OPTIONS && "*".equals(path)) {
                writeInvalidRequestPathResponse(streamId, headers);
                return;
            }

            // Validate the 'content-length' header if exists.
            final String contentLengthStr = headers.get(HttpHeaderNames.CONTENT_LENGTH);
            if (contentLengthStr != null) {
                long contentLength;
                try {
                    contentLength = Long.parseLong(contentLengthStr);
                } catch (NumberFormatException ignored) {
                    contentLength = -1;
                }
                if (contentLength < 0) {
                    writeErrorResponse(streamId, headers, HttpStatus.BAD_REQUEST,
                                       "Invalid content length", null);
                    return;
                }
            }

            if (hasInvalidExpectHeader) {
                writeErrorResponse(streamId, headers, HttpStatus.EXPECTATION_FAILED, null, null);
                return;
            }

            final RoutingContext routingCtx =
                    newRoutingContext(cfg, ctx.channel(),
                                      // scheme is http or https
                                      scheme == SCHEME_HTTP ? SessionProtocol.H2C : SessionProtocol.H2,
                                      headers, reqTarget);
            if (routingCtx.status().routeMustExist()) {
                try {
                    // Find the service that matches the path.
                    final Routed<ServiceConfig> routed =
                            routingCtx.virtualHost().findServiceConfig(routingCtx, true);
                    assert routed.isPresent();
                } catch (Throwable cause) {
                    logger.warn("{} Unexpected exception: {}", ctx.channel(), headers, cause);
                    writeErrorResponse(streamId, headers, HttpStatus.INTERNAL_SERVER_ERROR, null, cause);
                    return;
                }
            }

            final int id = ++nextId;
            final EventLoop eventLoop = ctx.channel().eventLoop();
            req = DecodedHttpRequest.of(endOfStream, eventLoop, id, streamId, headers, true,
                                        inboundTrafficController, routingCtx);
            requests.put(streamId, req);
            cfg.serverMetrics().increasePendingHttp2Requests();
            ctx.fireChannelRead(req);
        } else {
            if (!(req instanceof DecodedHttpRequestWriter)) {
                // Silently ignore the following HEADERS Frames of non-DecodedHttpRequestWriter. The request
                // stream is closed when receiving the first HEADERS frame, but the client might send
                // more frames before realizing it.
                logger.debug("{} Received a HEADERS frame for a finished stream: {}", ctx.channel(), streamId);
                return;
            }
            final HttpHeaders trailers = ArmeriaHttpUtil.toArmeria(nettyHeaders, true, endOfStream);
            final DecodedHttpRequestWriter decodedReq = (DecodedHttpRequestWriter) req;
            try {
                // Trailers is received. The decodedReq will be automatically closed.
                decodedReq.write(trailers);
            } catch (Throwable t) {
                decodedReq.close(t);
                throw Http2Exception.streamError(streamId, INTERNAL_ERROR, t,
                                                 "failed to consume a HEADERS frame");
            }
        }
    }

    @Override
    public void onHeadersRead(
            ChannelHandlerContext ctx, int streamId, Http2Headers headers,
            int streamDependency, short weight, boolean exclusive, int padding,
            boolean endOfStream) throws Http2Exception {
        onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }

    private boolean handle100Continue(int streamId, Http2Headers headers, HttpMethod method) {
        final CharSequence expectValue = headers.get(HttpHeaderNames.EXPECT);
        if (expectValue == null) {
            // No 'expect' header.
            return true;
        }

        // '100-continue' is the only allowed expectation.
        if (!AsciiString.contentEqualsIgnoreCase(HttpHeaderValues.CONTINUE, expectValue)) {
            return false;
        }

        // Send a '100 Continue' response.
        assert encoder != null;
        encoder.writeHeaders(0 /* unused */, streamId, CONTINUE_RESPONSE, false, method);

        // Remove the 'expect' header so that it's handled in a way invisible to a Service.
        headers.remove(HttpHeaderNames.EXPECT);
        return true;
    }

    @Override
    public void onStreamClosed(Http2Stream stream) {
        goAwayHandler.onStreamClosed(channel, stream);

        final DecodedHttpRequest req = requests.remove(stream.id());
        if (req != null && !req.isComplete()) {
            // Ignored if the stream has already been closed.
            req.close(ClosedStreamException.get());
        }
    }

    @Override
    public int onDataRead(
            ChannelHandlerContext ctx, int streamId, ByteBuf data,
            int padding, boolean endOfStream) throws Http2Exception {
        keepAliveChannelRead(false);

        final int dataLength = data.readableBytes();
        final DecodedHttpRequest req = requests.get(streamId);
        final boolean logInvalidStream;
        if (req == null) {
            if (encoder == null || encoder.findStream(streamId) == null) {
                throw connectionError(PROTOCOL_ERROR, "received a DATA frame for an unknown stream: %d",
                                      streamId);
            } else {
                // Received a frame for the stream we rejected.
                logInvalidStream = true;
            }
        } else {
            if (req.isResponseAborted()) {
                // Discard the DATA frame received after the response has been aborted
                // because an aborted response means we have finished handling the
                // request.
                return dataLength + padding;
            }

            // Silently ignore the following DATA Frames of non-DecodedHttpRequestWriter.
            // The request stream is closed when receiving the HEADERS frame, but the client might send
            // more frames before realizing it.
            logInvalidStream = !(req instanceof DecodedHttpRequestWriter);
        }

        if (logInvalidStream) {
            logger.debug("{} Received a DATA frame for a finished stream: {} / headers: {}",
                         ctx.channel(), streamId, req != null ? req.headers() : "<unknown>");
            return dataLength + padding;
        }

        if (dataLength == 0) {
            // Received an empty DATA frame
            if (endOfStream) {
                req.close();
            }
            return padding;
        }

        final DecodedHttpRequestWriter decodedReq = (DecodedHttpRequestWriter) req;
        decodedReq.increaseTransferredBytes(dataLength);

        final long maxContentLength = decodedReq.maxRequestLength();
        final long transferredLength = decodedReq.transferredBytes();
        if (maxContentLength > 0 && transferredLength > maxContentLength) {
            assert encoder != null;
            final ContentTooLargeException cause =
                    ContentTooLargeException.builder()
                                            .maxContentLength(maxContentLength)
                                            .contentLength(decodedReq.headers())
                                            .transferred(transferredLength)
                                            .build();

            final boolean shouldReset = !endOfStream;

            final HttpStatusException httpStatusException =
                    HttpStatusException.of(HttpStatus.REQUEST_ENTITY_TOO_LARGE, cause);
            decodedReq.setShouldResetOnlyIfRemoteIsOpen(shouldReset);
            decodedReq.abortResponse(httpStatusException, true);
        } else if (decodedReq.isOpen()) {
            try {
                // The decodedReq will be automatically closed if endOfStream is true.
                decodedReq.write(HttpData.wrap(data.retain()).withEndOfStream(endOfStream));
            } catch (Throwable t) {
                decodedReq.close(t);
                throw Http2Exception.streamError(streamId, INTERNAL_ERROR, t,
                                                 "failed to consume a DATA frame");
            }
        }

        // All bytes have been processed.
        return dataLength + padding;
    }

    private void writeInvalidRequestPathResponse(int streamId, @Nullable RequestHeaders headers) {
        writeErrorResponse(streamId, headers, HttpStatus.BAD_REQUEST,
                           "Invalid request path", null);
    }

    private void writeUnsupportedMethodResponse(int streamId, RequestHeaders headers) {
        writeErrorResponse(streamId, headers, HttpStatus.METHOD_NOT_ALLOWED,
                           "Unsupported method", null);
    }

    private void writeErrorResponse(int streamId, @Nullable RequestHeaders headers,
                                    HttpStatus status, @Nullable String message,
                                    @Nullable Throwable cause) {
        assert encoder != null;
        encoder.writeErrorResponse(0 /* unused */, streamId,
                                   cfg.defaultVirtualHost().fallbackServiceConfig(),
                                   headers, status, message, cause);
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
        keepAliveChannelRead(false);
        final DecodedHttpRequest req = requests.get(streamId);
        if (req == null) {
            if (encoder == null || encoder.findStream(streamId) == null) {
                throw connectionError(PROTOCOL_ERROR,
                                      "received a RST_STREAM frame for an unknown stream: %d", streamId);
            } else {
                // Received a frame for the stream we rejected.
                logger.debug("{} Received a RST_STREAM frame for a finished stream: {}",
                             ctx.channel(), streamId);
                return;
            }
        }

        final ClosedStreamException cause =
                new ClosedStreamException("received a RST_STREAM frame: " + Http2Error.valueOf(errorCode));
        req.abortResponse(cause, /* cancel */ true);
    }

    @Override
    public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                  Http2Headers headers, int padding) throws Http2Exception {
        throw connectionError(PROTOCOL_ERROR, "received a PUSH_PROMISE frame which only a server can send");
    }

    @Override
    public void onGoAwaySent(int lastStreamId, long errorCode, ByteBuf debugData) {
        goAwayHandler.onGoAwaySent(channel, lastStreamId, errorCode, debugData);
    }

    @Override
    public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
        goAwayHandler.onGoAwayReceived(channel, lastStreamId, errorCode, debugData);
    }

    @Override
    public void onPingAckRead(final ChannelHandlerContext ctx, final long data) {
        if (keepAliveHandler.isHttp2()) {
            keepAliveHandler.onPingAck(data);
        }
    }

    @Override
    public void onPingRead(ChannelHandlerContext ctx, long data) {
        keepAliveHandler.onPing();
    }

    private void keepAliveChannelRead(boolean increaseNumRequests) {
        keepAliveHandler.onReadOrWrite();
        if (increaseNumRequests) {
            keepAliveHandler.increaseNumRequests();
        }
    }
}

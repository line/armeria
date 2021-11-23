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
import com.linecorp.armeria.common.ResponseHeaders;
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
    private final String scheme;
    @Nullable
    private ServerHttp2ObjectEncoder encoder;

    private final InboundTrafficController inboundTrafficController;
    private final KeepAliveHandler keepAliveHandler;
    private final Http2GoAwayHandler goAwayHandler;
    private final IntObjectMap<DecodedHttpRequest> requests = new IntObjectHashMap<>();
    private int nextId;

    Http2RequestDecoder(ServerConfig cfg, Channel channel, String scheme, KeepAliveHandler keepAliveHandler) {
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
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding,
                              boolean endOfStream) throws Http2Exception {
        keepAliveChannelRead(true);
        DecodedHttpRequest req = requests.get(streamId);
        if (req == null) {
            assert encoder != null;

            // Validate the method.
            final CharSequence methodText = headers.method();
            if (methodText == null) {
                writeErrorResponse(streamId, HttpStatus.BAD_REQUEST, "Missing method", null);
                return;
            }

            // Reject a request with an unsupported method.
            // Note: Accept a CONNECT request with a :protocol header, as defined in:
            //       https://datatracker.ietf.org/doc/html/rfc8441#section-4
            final HttpMethod method = HttpMethod.tryParse(methodText.toString());
            if (method == null ||
                method == HttpMethod.CONNECT && !headers.contains(HttpHeaderNames.PROTOCOL)) {
                writeErrorResponse(streamId, HttpStatus.METHOD_NOT_ALLOWED, "Unsupported method", null);
                return;
            }

            // Validate the 'content-length' header if exists.
            if (headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                final long contentLength = headers.getLong(HttpHeaderNames.CONTENT_LENGTH, -1L);
                if (contentLength < 0) {
                    writeErrorResponse(streamId, HttpStatus.BAD_REQUEST, "Invalid content length", null);
                    return;
                }
            }

            if (!handle100Continue(streamId, headers)) {
                writeErrorResponse(streamId, HttpStatus.EXPECTATION_FAILED, null, null);
                return;
            }

            final EventLoop eventLoop = ctx.channel().eventLoop();
            final RequestHeaders armeriaRequestHeaders =
                    ArmeriaHttpUtil.toArmeriaRequestHeaders(ctx, headers, endOfStream, scheme, cfg);

            final RoutingContext routingCtx = newRoutingContext(cfg, ctx, armeriaRequestHeaders);
            final Routed<ServiceConfig> routed;
            if (routingCtx instanceof EarlyResponseRoutingContext) {
                routed = null;
            } else {
                try {
                    // Find the service that matches the path.
                    routed = routingCtx.virtualHost().findServiceConfig(routingCtx, true);
                } catch (Throwable cause) {
                    logger.warn("{} Unexpected exception: {}", ctx.channel(), armeriaRequestHeaders, cause);
                    writeErrorResponse(streamId, HttpStatus.INTERNAL_SERVER_ERROR, null, cause);
                    return;
                }
                assert routed.isPresent();
            }

            final int id = ++nextId;
            req = DecodedHttpRequest.of(endOfStream, eventLoop, id, streamId, armeriaRequestHeaders, true,
                                        inboundTrafficController, routingCtx, routed);
            requests.put(streamId, req);
            // AggregatingDecodedHttpRequest will be fired after all objects are collected.
            if (!(req instanceof AggregatingDecodedHttpRequest)) {
                ctx.fireChannelRead(req);
            }
        } else {
            final HttpHeaders trailers = ArmeriaHttpUtil.toArmeria(headers, true, endOfStream);
            final DecodedHttpRequestWriter decodedReq = (DecodedHttpRequestWriter) req;
            try {
                // Trailers is received. The decodedReq will be automatically closed.
                decodedReq.write(trailers);
                if (req instanceof AggregatingDecodedHttpRequest) {
                    // AggregatingDecodedHttpRequest can be fired now.
                    ctx.fireChannelRead(req);
                }
            } catch (Throwable t) {
                decodedReq.close(t);
                throw connectionError(INTERNAL_ERROR, t, "failed to consume a HEADERS frame");
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

    private boolean handle100Continue(int streamId, Http2Headers headers) {
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
        encoder.writeHeaders(0 /* unused */, streamId, CONTINUE_RESPONSE, false);

        // Remove the 'expect' header so that it's handled in a way invisible to a Service.
        headers.remove(HttpHeaderNames.EXPECT);
        return true;
    }

    @Override
    public void onStreamClosed(Http2Stream stream) {
        goAwayHandler.onStreamClosed(channel, stream);

        final DecodedHttpRequest req = requests.remove(stream.id());
        if (req != null) {
            // Ignored if the stream has already been closed.
            req.close(ClosedStreamException.get());
        }
    }

    @Override
    public int onDataRead(
            ChannelHandlerContext ctx, int streamId, ByteBuf data,
            int padding, boolean endOfStream) throws Http2Exception {
        keepAliveChannelRead(false);

        final DecodedHttpRequest req = requests.get(streamId);
        if (req == null) {
            throw connectionError(PROTOCOL_ERROR, "received a DATA Frame for an unknown stream: %d",
                                  streamId);
        }

        final int dataLength = data.readableBytes();
        if (dataLength == 0) {
            // Received an empty DATA frame
            if (endOfStream) {
                req.close();
                if (req instanceof AggregatingDecodedHttpRequest) {
                    ctx.fireChannelRead(req);
                }
            }
            return padding;
        }

        final DecodedHttpRequestWriter decodedReq = (DecodedHttpRequestWriter) req;
        decodedReq.increaseTransferredBytes(dataLength);

        final long maxContentLength = decodedReq.maxRequestLength();
        final long transferredLength = decodedReq.transferredBytes();
        if (maxContentLength > 0 && transferredLength > maxContentLength) {
            assert encoder != null;
            final Http2Stream stream = encoder.findStream(streamId);
            if (isWritable(stream)) {
                final ContentTooLargeException cause =
                        ContentTooLargeException.builder()
                                                .maxContentLength(maxContentLength)
                                                .contentLength(req.headers())
                                                .transferred(transferredLength)
                                                .build();

                writeErrorResponse(streamId, HttpStatus.REQUEST_ENTITY_TOO_LARGE, null, cause);

                if (!decodedReq.isComplete()) {
                    decodedReq.close(cause);
                }
            } else {
                // The response has been started already. Abort the request and let the response continue.
                decodedReq.abort();
            }
        } else if (decodedReq.isOpen()) {
            try {
                // The decodedReq will be automatically closed if endOfStream is true.
                decodedReq.write(HttpData.wrap(data.retain()).withEndOfStream(endOfStream));
                if (endOfStream && decodedReq instanceof AggregatingDecodedHttpRequest) {
                    // AggregatingDecodedHttpRequest is now ready to be fired.
                    ctx.fireChannelRead(req);
                }
            } catch (Throwable t) {
                decodedReq.close(t);
                throw connectionError(INTERNAL_ERROR, t, "failed to consume a DATA frame");
            }
        }

        // All bytes have been processed.
        return dataLength + padding;
    }

    private static boolean isWritable(@Nullable Http2Stream stream) {
        if (stream == null) {
            return false;
        }
        switch (stream.state()) {
            case OPEN:
            case HALF_CLOSED_REMOTE:
                return !stream.isHeadersSent();
            default:
                return false;
        }
    }

    private void writeErrorResponse(
            int streamId, HttpStatus status, @Nullable String message, @Nullable Throwable cause) {
        assert encoder != null;
        encoder.writeErrorResponse(0 /* unused */, streamId,
                                   cfg.defaultVirtualHost().fallbackServiceConfig(),
                                   status, message, cause);
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
        keepAliveChannelRead(false);
        final DecodedHttpRequest req = requests.get(streamId);
        if (req == null) {
            throw connectionError(PROTOCOL_ERROR,
                                  "received a RST_STREAM frame for an unknown stream: %d", streamId);
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

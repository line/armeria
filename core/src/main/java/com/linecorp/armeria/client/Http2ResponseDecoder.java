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

package com.linecorp.armeria.client;

import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;

final class Http2ResponseDecoder extends HttpResponseDecoder implements Http2Connection.Listener,
                                                                        Http2FrameListener {

    private static final Logger logger = LoggerFactory.getLogger(Http2ResponseDecoder.class);

    private final Http2Connection conn;
    private final Http2ConnectionEncoder encoder;

    Http2ResponseDecoder(Http2Connection conn, Channel channel, Http2ConnectionEncoder encoder) {
        super(channel);
        this.conn = conn;
        this.encoder = encoder;
    }

    @Override
    HttpResponseWrapper addResponse(
            int id, HttpRequest req, DecodedHttpResponse res, RequestLogBuilder logBuilder,
            long responseTimeoutMillis, long maxContentLength) {

        final HttpResponseWrapper resWrapper =
                super.addResponse(id, req, res, logBuilder, responseTimeoutMillis, maxContentLength);

        resWrapper.completionFuture().whenCompleteAsync((unused, cause) -> {
            // Ensure that the scheduled timeout is not executed.
            resWrapper.cancelTimeout();
            if (cause != null) {
                // We are not closing the connection but just send a RST_STREAM,
                // so we have to remove the response manually.
                removeResponse(id);

                // Reset the stream.
                final int streamId = idToStreamId(id);
                if (conn.streamMayHaveExisted(streamId)) {
                    final ChannelHandlerContext ctx = channel().pipeline().lastContext();
                    encoder.writeRstStream(ctx, streamId, Http2Error.CANCEL.code(), ctx.newPromise());
                    ctx.flush();
                }
            }
        }, channel().eventLoop());
        return resWrapper;
    }

    @Override
    public void onStreamAdded(Http2Stream stream) {}

    @Override
    public void onStreamActive(Http2Stream stream) {}

    @Override
    public void onStreamHalfClosed(Http2Stream stream) {}

    @Override
    public void onStreamClosed(Http2Stream stream) {
        final HttpResponseWrapper res = getResponse(streamIdToId(stream.id()), true);
        if (res != null) {
            res.close(ClosedSessionException.get());
        }
    }

    @Override
    public void onStreamRemoved(Http2Stream stream) {}

    @Override
    public void onGoAwaySent(int lastStreamId, long errorCode, ByteBuf debugData) {}

    @Override
    public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {}

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
        ctx.fireChannelRead(settings);
    }

    @Override
    public void onSettingsAckRead(ChannelHandlerContext ctx) {}

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding,
                              boolean endOfStream) throws Http2Exception {
        HttpResponseWrapper res = getResponse(streamIdToId(streamId), endOfStream);
        if (res == null) {
            if (conn.streamMayHaveExisted(streamId)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} Received a late HEADERS frame for a closed stream: {}",
                                 ctx.channel(), streamId);
                }
                return;
            }

            throw connectionError(PROTOCOL_ERROR, "received a HEADERS frame for an unknown stream: %d",
                                  streamId);
        }

        final HttpHeaders converted = ArmeriaHttpUtil.toArmeria(headers);
        try {
            res.scheduleTimeout(ctx);
            res.write(converted);
        } catch (Throwable t) {
            res.close(t);
            throw connectionError(INTERNAL_ERROR, t, "failed to consume a HEADERS frame");
        }

        if (endOfStream) {
            res.close();
        }
    }

    @Override
    public void onHeadersRead(
            ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency,
            short weight, boolean exclusive, int padding, boolean endOfStream) throws Http2Exception {

        onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }

    @Override
    public int onDataRead(
            ChannelHandlerContext ctx, int streamId, ByteBuf data,
            int padding, boolean endOfStream) throws Http2Exception {

        final int dataLength = data.readableBytes();
        final HttpResponseWrapper res = getResponse(streamIdToId(streamId), endOfStream);
        if (res == null) {
            if (conn.streamMayHaveExisted(streamId)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} Received a late DATA frame for a closed stream: {}",
                                 ctx.channel(), streamId);
                }
                return dataLength + padding;
            }

            throw connectionError(PROTOCOL_ERROR, "received a DATA frame for an unknown stream: %d",
                                  streamId);
        }

        final long maxContentLength = res.maxContentLength();
        if (maxContentLength > 0 && res.writtenBytes() > maxContentLength - dataLength) {
            res.close(ContentTooLargeException.get());
            throw connectionError(INTERNAL_ERROR,
                                  "content length too large: %d + %d > %d (stream: %d)",
                                  res.writtenBytes(), dataLength, maxContentLength, streamId);
        }

        try {
            res.write(HttpData.of(data));
        } catch (Throwable t) {
            res.close(t);
            throw connectionError(INTERNAL_ERROR, t, "failed to consume a DATA frame");
        }

        if (endOfStream) {
            res.close();
        }

        // All bytes have been processed.
        return dataLength + padding;
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
        final HttpResponseWrapper res = removeResponse(streamIdToId(streamId));
        if (res == null) {
            if (conn.streamMayHaveExisted(streamId)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} Received a late RST_STREAM frame for a closed stream: {}",
                                 ctx.channel(), streamId);
                }
            } else {
                throw connectionError(PROTOCOL_ERROR,
                                      "received a RST_STREAM frame for an unknown stream: %d", streamId);
            }
            return;
        }

        res.close(ClosedSessionException.get());
    }

    @Override
    public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                  Http2Headers headers, int padding) {}

    @Override
    public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency, short weight,
                               boolean exclusive) {}

    @Override
    public void onPingRead(ChannelHandlerContext ctx, ByteBuf data) {}

    @Override
    public void onPingAckRead(ChannelHandlerContext ctx, ByteBuf data) {}

    @Override
    public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {}

    @Override
    public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {}

    @Override
    public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags,
                               ByteBuf payload) {}

    private static int streamIdToId(int streamId) {
        return streamId - 1 >>> 1;
    }

    private static int idToStreamId(int id) {
        return (id << 1) + 1;
    }
}

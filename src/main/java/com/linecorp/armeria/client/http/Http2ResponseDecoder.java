/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.http;

import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatusClass;
import com.linecorp.armeria.internal.http.ArmeriaHttpUtil;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;

final class Http2ResponseDecoder extends HttpResponseDecoder implements Http2Connection.Listener,
                                                                        Http2FrameListener {

    Http2ResponseDecoder(Channel channel) {
        super(channel);
    }

    @Override
    public void onStreamAdded(Http2Stream stream) {}

    @Override
    public void onStreamActive(Http2Stream stream) {}

    @Override
    public void onStreamHalfClosed(Http2Stream stream) {}

    @Override
    public void onStreamClosed(Http2Stream stream) {}

    @Override
    public void onStreamRemoved(Http2Stream stream) {}

    @Override
    public void onPriorityTreeParentChanged(Http2Stream stream, Http2Stream oldParent) {}

    @Override
    public void onPriorityTreeParentChanging(Http2Stream stream, Http2Stream newParent) {}

    @Override
    public void onWeightChanged(Http2Stream stream, short oldWeight) {}

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
        HttpResponseWrapper res = getResponse(id(streamId), endOfStream);
        if (res == null) {
            throw connectionError(PROTOCOL_ERROR, "received a HEADERS frame for an unknown stream: %d",
                                  streamId);
        }

        final HttpHeaders converted = ArmeriaHttpUtil.toArmeria(headers);
        if (converted.status().codeClass() != HttpStatusClass.INFORMATIONAL) {
            res.scheduleTimeout(ctx);
        }

        try {
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
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency,
                              short weight, boolean exclusive, int padding, boolean endOfStream)
            throws Http2Exception {
        onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream)
            throws Http2Exception {

        final HttpResponseWrapper res = getResponse(id(streamId), endOfStream);
        if (res == null) {
            throw connectionError(PROTOCOL_ERROR, "received a DATA frame for an unknown stream: %d",
                                  streamId);
        }

        final int dataLength = data.readableBytes();
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
        final HttpResponseWriter res = removeResponse(id(streamId));
        if (res == null) {
            throw connectionError(PROTOCOL_ERROR,
                                  "received a RST_STREAM frame for an unknown stream: %d", streamId);
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

    private static int id(int streamId) {
        return streamId - 1 >>> 1;
    }
}

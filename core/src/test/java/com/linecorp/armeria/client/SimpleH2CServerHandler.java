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

package com.linecorp.armeria.client;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.CharsetUtil;

class SimpleH2CServerHandler extends Http2ConnectionHandler implements Http2FrameListener {

    // Copied from Netty HTTP/2 server examples and simplified.

    private static final ByteBuf RESPONSE_BYTES =
            unreleasableBuffer(copiedBuffer("Hello World", CharsetUtil.UTF_8));

    SimpleH2CServerHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                           Http2Settings initialSettings) {
        super(decoder, encoder, initialSettings);
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
                              Http2Headers headers, int padding, boolean endOfStream) {
        if (endOfStream) {
            final ByteBuf content = ctx.alloc().buffer();
            content.writeBytes(RESPONSE_BYTES.duplicate());
            ctx.flush();
            ByteBufUtil.writeAscii(content, " - via HTTP/2");
            sendResponse(ctx, streamId, OK, content);
        }
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
                              int streamDependency,
                              short weight, boolean exclusive, int padding, boolean endOfStream) {
        onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
                          boolean endOfStream) {
        final int processed = data.readableBytes() + padding;
        if (endOfStream) {
            sendResponse(ctx, streamId, OK, data.retain());
        }
        return processed;
    }

    /**
     * Sends an {@link Http2Headers} with the specified {@link HttpResponseStatus} and
     * {@code DATA} with the specified {@code payload}.
     */
    protected void sendResponse(ChannelHandlerContext ctx, int streamId, HttpResponseStatus status,
                                ByteBuf payload) {
        final Http2Headers headers = new DefaultHttp2Headers().status(status.codeAsText());
        encoder().writeHeaders(ctx, streamId, headers, 0, false, ctx.newPromise());
        encoder().writeData(ctx, streamId, payload, 0, true, ctx.newPromise());
    }

    // NO-OPs

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {}

    @Override
    public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency,
                               short weight, boolean exclusive) {}

    @Override
    public void onSettingsAckRead(ChannelHandlerContext ctx) {}

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {}

    @Override
    public void onPingRead(ChannelHandlerContext ctx, long data) {}

    @Override
    public void onPingAckRead(ChannelHandlerContext ctx, long data) {}

    @Override
    public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                  Http2Headers headers, int padding) {}

    @Override
    public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode,
                             ByteBuf debugData) {}

    @Override
    public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {}

    @Override
    public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId,
                               Http2Flags flags, ByteBuf payload) {}
}

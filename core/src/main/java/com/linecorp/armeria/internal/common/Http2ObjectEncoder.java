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

package com.linecorp.armeria.internal.common;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.stream.ClosedStreamException;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Stream;

public abstract class Http2ObjectEncoder implements HttpObjectEncoder {
    private final ChannelHandlerContext ctx;
    private final Http2ConnectionEncoder encoder;
    private final KeepAliveHandler keepAliveHandler;
    private volatile boolean closed;

    protected Http2ObjectEncoder(ChannelHandlerContext connectionHandlerCtx,
                                 AbstractHttp2ConnectionHandler connectionHandler) {
        ctx = connectionHandlerCtx;
        encoder = connectionHandler.encoder();
        keepAliveHandler = connectionHandler.keepAliveHandler();
    }

    @Override
    public final Channel channel() {
        return ctx.channel();
    }

    protected final ChannelHandlerContext ctx() {
        return ctx;
    }

    protected final Http2ConnectionEncoder encoder() {
        return encoder;
    }

    @Override
    public final KeepAliveHandler keepAliveHandler() {
        return keepAliveHandler;
    }

    @Override
    public final ChannelFuture doWriteData(int id, int streamId, HttpData data, boolean endStream) {
        if (isStreamPresentAndWritable(streamId)) {
            final KeepAliveHandler keepAliveHandler = keepAliveHandler();
            keepAliveHandler.onReadOrWrite();
            // Write to an existing stream.
            return encoder.writeData(ctx, streamId, toByteBuf(data), 0, endStream, ctx.newPromise());
        }

        if (encoder.connection().local().mayHaveCreatedStream(streamId)) {
            // Can't write to an outdated (closed) stream.
            data.close();
            return data.isEmpty() ? ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
                                  : newFailedFuture(ClosedStreamException.get());
        }

        // Cannot start a new stream with a DATA frame. It must start with a HEADERS frame.
        data.close();
        return newFailedFuture(new IllegalStateException(
                "Trying to write data to the closed stream " + streamId +
                " or start a new stream with a DATA frame"));
    }

    @Override
    public final ChannelFuture doWriteReset(int id, int streamId, Http2Error error) {
        final Http2Stream stream = encoder.connection().stream(streamId);
        // Send a RST_STREAM frame only for an active stream which did not send a RST_STREAM frame already.
        if (stream != null && !stream.isResetSent()) {
            return encoder.writeRstStream(ctx, streamId, error.code(), ctx.newPromise());
        }

        return ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public final boolean isWritable(int id, int streamId) {
        return isStreamPresentAndWritable(streamId);
    }

    /**
     * Returns {@code true} if the stream with the given {@code streamId} has been created and is writable.
     * Note that this method will return {@code false} for the stream which was not created yet.
     */
    protected final boolean isStreamPresentAndWritable(int streamId) {
        final Http2Stream stream = encoder.connection().stream(streamId);
        if (stream == null) {
            return false;
        }

        switch (stream.state()) {
            case RESERVED_LOCAL:
            case OPEN:
            case HALF_CLOSED_REMOTE:
                return true;
            default:
                // The response has been sent already.
                return false;
        }
    }

    @Override
    public final void close() {
        closed = true;
        keepAliveHandler().destroy();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}

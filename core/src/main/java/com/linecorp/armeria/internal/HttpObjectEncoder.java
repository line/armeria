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

package com.linecorp.armeria.internal;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.ReferenceCountUtil;

/**
 * Converts an {@link HttpObject} into a protocol-specific object and writes it into a {@link Channel}.
 */
public abstract class HttpObjectEncoder {

    private volatile boolean closed;

    /**
     * Writes an {@link HttpHeaders}.
     */
    public final ChannelFuture writeHeaders(
            ChannelHandlerContext ctx, int id, int streamId, HttpHeaders headers, boolean endStream) {

        assert ctx.channel().eventLoop().inEventLoop();

        if (closed) {
            return newFailedFuture(ctx);
        }

        return doWriteHeaders(ctx, id, streamId, headers, endStream);
    }

    protected abstract ChannelFuture doWriteHeaders(
            ChannelHandlerContext ctx, int id, int streamId, HttpHeaders headers, boolean endStream);

    /**
     * Writes an {@link HttpData}.
     */
    public final ChannelFuture writeData(
            ChannelHandlerContext ctx, int id, int streamId, HttpData data, boolean endStream) {

        assert ctx.channel().eventLoop().inEventLoop();

        if (closed) {
            ReferenceCountUtil.safeRelease(data);
            return newFailedFuture(ctx);
        }

        return doWriteData(ctx, id, streamId, data, endStream);
    }

    protected abstract ChannelFuture doWriteData(
            ChannelHandlerContext ctx, int id, int streamId, HttpData data, boolean endStream);

    /**
     * Resets the specified stream. If the session protocol does not support multiplexing or the connection
     * is in unrecoverable state, the connection will be closed. For example, in an HTTP/1 connection, this
     * will lead the connection to be closed immediately or after the previous requests that are not reset.
     */
    public final ChannelFuture writeReset(ChannelHandlerContext ctx, int id, int streamId, Http2Error error) {

        if (closed) {
            return newFailedFuture(ctx);
        }

        return doWriteReset(ctx, id, streamId, error);
    }

    protected abstract ChannelFuture doWriteReset(
            ChannelHandlerContext ctx, int id, int streamId, Http2Error error);

    /**
     * Releases the resources related with this encoder and fails any unfinished writes.
     */
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        doClose();
    }

    protected abstract void doClose();

    private static ChannelFuture newFailedFuture(ChannelHandlerContext ctx) {
        return ctx.newFailedFuture(ClosedSessionException.get());
    }

    protected static ByteBuf toByteBuf(ChannelHandlerContext ctx, HttpData data) {
        if (data instanceof ByteBufHolder) {
            return ((ByteBufHolder) data).content();
        }
        final ByteBuf buf = ctx.alloc().directBuffer(data.length(), data.length());
        buf.writeBytes(data.array(), data.offset(), data.length());
        return buf;
    }
}

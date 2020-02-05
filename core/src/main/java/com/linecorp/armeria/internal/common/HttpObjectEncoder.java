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

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.ReferenceCountUtil;

/**
 * Converts an {@link HttpObject} into a protocol-specific object and writes it into a {@link Channel}.
 */
public abstract class HttpObjectEncoder {

    private volatile boolean closed;

    protected abstract Channel channel();

    protected EventLoop eventLoop() {
        return channel().eventLoop();
    }

    /**
     * Writes an {@link HttpHeaders}.
     */
    public final ChannelFuture writeHeaders(int id, int streamId, HttpHeaders headers, boolean endStream) {

        assert eventLoop().inEventLoop();

        if (closed) {
            return newClosedSessionFuture();
        }

        return doWriteHeaders(id, streamId, headers, endStream);
    }

    protected abstract ChannelFuture doWriteHeaders(int id, int streamId, HttpHeaders headers,
                                                    boolean endStream);

    /**
     * Writes an {@link HttpData}.
     */
    public final ChannelFuture writeData(int id, int streamId, HttpData data, boolean endStream) {

        assert eventLoop().inEventLoop();

        if (closed) {
            ReferenceCountUtil.safeRelease(data);
            return newClosedSessionFuture();
        }

        return doWriteData(id, streamId, data, endStream);
    }

    protected abstract ChannelFuture doWriteData(int id, int streamId, HttpData data, boolean endStream);

    /**
     * Resets the specified stream. If the session protocol does not support multiplexing or the connection
     * is in unrecoverable state, the connection will be closed. For example, in an HTTP/1 connection, this
     * will lead the connection to be closed immediately or after the previous requests that are not reset.
     */
    public final ChannelFuture writeReset(int id, int streamId, Http2Error error) {

        if (closed) {
            return newClosedSessionFuture();
        }

        return doWriteReset(id, streamId, error);
    }

    protected abstract ChannelFuture doWriteReset(int id, int streamId, Http2Error error);

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

    /**
     * Returns {@code true} if the specified {@code id} and {@code streamId} is writable.
     */
    public abstract boolean isWritable(int id, int streamId);

    protected abstract void doClose();

    protected final ChannelFuture newClosedSessionFuture() {
        return newFailedFuture(ClosedSessionException.get());
    }

    protected final ChannelFuture newFailedFuture(Throwable cause) {
        return channel().newFailedFuture(cause);
    }

    protected final ByteBuf toByteBuf(HttpData data) {
        if (data instanceof ByteBufHolder) {
            return ((ByteBufHolder) data).content();
        }
        final ByteBuf buf = channel().alloc().directBuffer(data.length(), data.length());
        buf.writeBytes(data.array());
        return buf;
    }
}

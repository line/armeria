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

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Error;

/**
 * Converts an {@link HttpObject} into a protocol-specific object and writes it into a {@link Channel}.
 */
public interface HttpObjectEncoder {

    Channel channel();

    default EventLoop eventLoop() {
        return channel().eventLoop();
    }

    KeepAliveHandler keepAliveHandler();

    /**
     * Writes an HTTP trailers.
     */
    default ChannelFuture writeTrailers(int id, int streamId, HttpHeaders headers) {
        assert eventLoop().inEventLoop();

        if (isClosed()) {
            return newClosedSessionFuture();
        }

        return doWriteTrailers(id, streamId, headers);
    }

    ChannelFuture doWriteTrailers(int id, int streamId, HttpHeaders headers);

    /**
     * Writes an {@link HttpData}.
     */
    default ChannelFuture writeData(int id, int streamId, HttpData data, boolean endStream) {

        assert eventLoop().inEventLoop();

        if (isClosed()) {
            data.close();
            return newClosedSessionFuture();
        }

        return doWriteData(id, streamId, data, endStream);
    }

    ChannelFuture doWriteData(int id, int streamId, HttpData data, boolean endStream);

    /**
     * Resets the specified stream. If the session protocol does not support multiplexing or the connection
     * is in unrecoverable state, the connection will be closed. For example, in an HTTP/1 connection, this
     * will lead the connection to be closed immediately or after the previous requests that are not reset.
     */
    default ChannelFuture writeReset(int id, int streamId, Http2Error error) {

        if (isClosed()) {
            return newClosedSessionFuture();
        }

        return doWriteReset(id, streamId, error);
    }

    ChannelFuture doWriteReset(int id, int streamId, Http2Error error);

    /**
     * Releases the resources related with this encoder and fails any unfinished writes.
     */
    void close();

    /**
     * Returns {@code true} if {@link #close()} is called.
     */
    boolean isClosed();

    /**
     * Returns {@code true} if the specified {@code id} and {@code streamId} is writable.
     */
    boolean isWritable(int id, int streamId);

    default ChannelFuture newClosedSessionFuture() {
        return newFailedFuture(ClosedSessionException.get());
    }

    default ChannelFuture newFailedFuture(Throwable cause) {
        return channel().newFailedFuture(cause);
    }

    default ByteBuf toByteBuf(HttpData data) {
        final ByteBuf buf = data.byteBuf(ByteBufAccessMode.FOR_IO);
        data.close();
        return buf;
    }

    default ByteBuf toByteBuf(HttpData data, int offset, int length) {
        final ByteBuf buf = data.byteBuf(offset, length, ByteBufAccessMode.FOR_IO);
        data.close();
        return buf;
    }
}

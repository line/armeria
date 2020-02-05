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

import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream.State;
import io.netty.handler.codec.http2.Http2StreamVisitor;

/**
 * An {@link Http2ConnectionHandler} with some workarounds and additional extension points.
 */
public abstract class AbstractHttp2ConnectionHandler extends Http2ConnectionHandler {

    private static final Logger logger = LoggerFactory.getLogger(AbstractHttp2ConnectionHandler.class);

    /**
     * XXX(trustin): Don't know why, but {@link Http2ConnectionHandler} does not close the last stream
     *               on a cleartext connection, so we make sure all streams are closed.
     */
    private static final Http2StreamVisitor closeAllStreams = stream -> {
        if (stream.state() != State.CLOSED) {
            stream.close();
        }
        return true;
    };

    private boolean closing;
    private boolean handlingConnectionError;

    /**
     * Creates a new instance.
     */
    protected AbstractHttp2ConnectionHandler(
            Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
        super(decoder, encoder, initialSettings);
    }

    /**
     * Returns {@code true} if {@link ChannelHandlerContext#close()} has been called.
     */
    public boolean isClosing() {
        return closing;
    }

    @Override
    protected void onConnectionError(ChannelHandlerContext ctx, boolean outbound,
                                     Throwable cause, Http2Exception http2Ex) {
        if (handlingConnectionError) {
            return;
        }

        handlingConnectionError = true;
        if (!Exceptions.isExpected(cause)) {
            logger.warn("{} HTTP/2 connection error:", ctx.channel(), cause);
        }
        super.onConnectionError(ctx, outbound, cause, filterHttp2Exception(cause, http2Ex));
    }

    private static Http2Exception filterHttp2Exception(Throwable cause, @Nullable Http2Exception http2Ex) {
        if (http2Ex != null) {
            return http2Ex;
        }

        // Do not let Netty use the exception message as debug data, just in case the exception message
        // exposes sensitive information.
        return new Http2Exception(INTERNAL_ERROR, null, cause);
    }

    @Override
    public ChannelFuture goAway(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData,
                                ChannelPromise promise) {
        if (!ctx.channel().isActive()) {
            // There's no point of sending a GOAWAY frame because the connection is over already.
            promise.unvoid().trySuccess();
            debugData.release();
            return promise;
        }

        return super.goAway(ctx, lastStreamId, errorCode, debugData, promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        if (!closing) {
            closing = true;

            if (needsImmediateDisconnection()) {
                connection().forEachActiveStream(closeAllStreams);
            }
        }

        super.close(ctx, promise);
    }

    /**
     * Returns {@code true} if the connection has to be closed immediately rather than sending a GOAWAY
     * frame and waiting for the remaining streams. This method should return {@code true} when:
     * <ul>
     *   <li>{@link ClientFactory} is being closed.</li>
     *   <li>{@link Server} is being stopped.</li>
     *   <li>Received a GOAWAY frame with non-OK error code.</li>
     * </ul>
     */
    protected abstract boolean needsImmediateDisconnection();
}

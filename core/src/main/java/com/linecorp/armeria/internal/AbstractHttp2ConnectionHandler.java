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

import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.Flags;
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
        super.onConnectionError(ctx, outbound, cause, filterHttp2Exception(cause, http2Ex));
    }

    private static Http2Exception filterHttp2Exception(Throwable cause, @Nullable Http2Exception http2Ex) {
        if (http2Ex != null) {
            return http2Ex;
        }

        if (Flags.verboseResponses()) {
            return new Http2Exception(INTERNAL_ERROR, goAwayDebugData(null, cause), cause);
        } else {
            // Do not let Netty use the exception message as debug data, just in case the exception message
            // exposes sensitive information.
            return new Http2Exception(INTERNAL_ERROR, null, cause);
        }
    }

    private static String goAwayDebugData(@Nullable Http2Exception http2Ex, @Nullable Throwable cause) {
        final String type;
        final String message;

        if (http2Ex != null) {
            type = http2Ex.getClass().getName();
            message = http2Ex.getMessage();
        } else {
            type = null;
            message = null;
            if (cause == null) {
                return "";
            }
        }

        final StringBuilder buf = new StringBuilder(256);
        if (type != null) {
            buf.append(", type: ");
            buf.append(type);
        }
        if (message != null) {
            buf.append(", message: ");
            buf.append(message);
        }
        if (cause != null) {
            buf.append(", cause: ");
            buf.append(Exceptions.traceText(cause));
        }

        return buf.substring(2); // Strip the leading comma.
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

            onCloseRequest(ctx);
        }

        super.close(ctx, promise);
    }

    /**
     * Invoked when a close request has been issued by {@link ChannelHandlerContext#close()} and all active
     * streams have been closed.
     */
    protected abstract void onCloseRequest(ChannelHandlerContext ctx) throws Exception;

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

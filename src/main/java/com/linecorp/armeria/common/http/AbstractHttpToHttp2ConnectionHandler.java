/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.common.http;

import static io.netty.handler.codec.http2.Http2CodecUtil.HTTP_UPGRADE_STREAM_ID;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Exception.StreamException;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream.State;
import io.netty.handler.codec.http2.Http2StreamVisitor;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.util.internal.EmptyArrays;

public abstract class AbstractHttpToHttp2ConnectionHandler extends HttpToHttp2ConnectionHandler {

    private static final StreamException REFUSED_STREAM_EXCEPTION =
            (StreamException) Http2Exception.streamError(
                    HTTP_UPGRADE_STREAM_ID, Http2Error.REFUSED_STREAM,
                    "The request sent with the upgrade request has been refused; try again.");

    static {
        REFUSED_STREAM_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
    }

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

    protected AbstractHttpToHttp2ConnectionHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                                   Http2Settings initialSettings, boolean validateHeaders) {
        super(decoder, encoder, initialSettings, validateHeaders);
    }

    public boolean isClosing() {
        return closing;
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        closing = true;

        // TODO(trustin): Remove this line once https://github.com/netty/netty/issues/4210 is fixed.
        connection().forEachActiveStream(closeAllStreams);

        onCloseRequest(ctx);
        super.close(ctx, promise);
    }

    protected abstract void onCloseRequest(ChannelHandlerContext ctx) throws Exception;
}

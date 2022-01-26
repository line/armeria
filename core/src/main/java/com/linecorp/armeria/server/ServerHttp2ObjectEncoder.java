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
package com.linecorp.armeria.server;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandler;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http2ObjectEncoder;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;
import com.linecorp.armeria.internal.common.util.HttpTimestampSupplier;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2RemoteFlowController;
import io.netty.handler.codec.http2.Http2Stream;

final class ServerHttp2ObjectEncoder extends Http2ObjectEncoder implements ServerHttpObjectEncoder {

    private final boolean enableServerHeader;
    private final boolean enableDateHeader;
    private boolean hasCalledChannelClose;

    ServerHttp2ObjectEncoder(ChannelHandlerContext connectionHandlerCtx,
                             AbstractHttp2ConnectionHandler connectionHandler,
                             boolean enableDateHeader, boolean enableServerHeader) {
        super(connectionHandlerCtx, connectionHandler);
        assert keepAliveHandler() instanceof Http2ServerKeepAliveHandler ||
               keepAliveHandler() instanceof NoopKeepAliveHandler;
        this.enableServerHeader = enableServerHeader;
        this.enableDateHeader = enableDateHeader;
    }

    @Override
    public ChannelFuture doWriteHeaders(int id, int streamId, ResponseHeaders headers, boolean endStream,
                                        boolean isTrailersEmpty) {
        if (!isStreamPresentAndWritable(streamId)) {
            // One of the following cases:
            // - Stream has been closed already.
            // - (bug) Server tried to send a response HEADERS frame before receiving a request HEADERS frame.
            return newFailedFuture(ClosedStreamException.get());
        }

        // TODO(alexc-db): decouple this from headers write and do it from inside the KeepAliveHandler.
        if (!hasCalledChannelClose && keepAliveHandler().needToCloseConnection()) {
            // Initiates channel close, connection will be closed after all streams are closed.
            ctx().channel().close();
            hasCalledChannelClose = true;
        }

        final Http2Headers converted = convertHeaders(headers, isTrailersEmpty);
        onKeepAliveReadOrWrite();
        return encoder().writeHeaders(ctx(), streamId, converted, 0, endStream, ctx().newPromise());
    }

    @Override
    public boolean isResponseHeadersSent(int id, int streamId) {
        final Http2Stream stream = findStream(streamId);
        if (stream == null) {
            return false;
        }
        return stream.isHeadersSent();
    }

    @Nullable
    Http2Stream findStream(int streamId) {
        return encoder().connection().stream(streamId);
    }

    private Http2Headers convertHeaders(ResponseHeaders inputHeaders, boolean isTrailersEmpty) {
        final HttpHeadersBuilder builder = inputHeaders.toBuilder();
        if (enableServerHeader && !inputHeaders.contains(HttpHeaderNames.SERVER)) {
            builder.add(HttpHeaderNames.SERVER, ArmeriaHttpUtil.SERVER_HEADER);
        }

        if (enableDateHeader && !inputHeaders.contains(HttpHeaderNames.DATE)) {
            builder.add(HttpHeaderNames.DATE, HttpTimestampSupplier.currentTime());
        }

        if (!isTrailersEmpty && inputHeaders.contains(HttpHeaderNames.CONTENT_LENGTH)) {
            // We don't apply chunked encoding when the content-length header is set, which would
            // prevent the trailers from being sent so we go ahead and remove content-length to force
            // chunked encoding.
            builder.remove(HttpHeaderNames.CONTENT_LENGTH);
        }
        return ArmeriaHttpUtil.toNettyHttp2ServerHeaders(builder);
    }

    @Override
    public ChannelFuture doWriteTrailers(int id, int streamId, HttpHeaders headers) {
        if (!isStreamPresentAndWritable(streamId)) {
            // One of the following cases:
            // - Stream has been closed already.
            // - (bug) Server tried to send a response HEADERS frame before receiving a request HEADERS frame.
            return newFailedFuture(ClosedStreamException.get());
        }

        final Http2Headers converted = ArmeriaHttpUtil.toNettyHttp2ServerTrailers(headers);
        onKeepAliveReadOrWrite();
        return encoder().writeHeaders(ctx(), streamId, converted, 0, true, ctx().newPromise());
    }

    private void onKeepAliveReadOrWrite() {
        keepAliveHandler().onReadOrWrite();
    }

    @Override
    public ChannelFuture writeErrorResponse(int id, int streamId,
                                            ServiceConfig serviceConfig,
                                            @Nullable RequestHeaders headers, HttpStatus status,
                                            @Nullable String message, @Nullable Throwable cause) {

        ChannelFuture future = ServerHttpObjectEncoder.super.writeErrorResponse(
                id, streamId, serviceConfig, headers, status, message, cause);

        final Http2Stream stream = findStream(streamId);
        if (stream != null) {
            // Ensure to flush the error response if it's flow-controlled so that it is sent
            // before an RST_STREAM frame.
            final Http2RemoteFlowController flowController = encoder().flowController();
            if (flowController.hasFlowControlled(stream)) {
                try {
                    flowController.writePendingBytes();
                } catch (Http2Exception ignored) {
                    // Just best-effort
                }
            }

            // Send RST_STREAM if the peer may still send something.
            if (stream.state().localSideOpen()) {
                future = encoder().writeRstStream(ctx(), streamId, Http2Error.CANCEL.code(),
                                                  ctx().voidPromise());
                ctx().flush();
            }
        }

        return future;
    }
}

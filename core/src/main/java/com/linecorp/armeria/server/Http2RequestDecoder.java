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

package com.linecorp.armeria.server;

import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;
import static io.netty.handler.codec.http2.Http2Exception.streamError;

import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.Http2GoAwayHandler;
import com.linecorp.armeria.internal.InboundTrafficController;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2EventAdapter;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.AsciiString;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;

final class Http2RequestDecoder extends Http2EventAdapter {

    private static final ByteBuf DATA_MISSING_METHOD =
            Unpooled.copiedBuffer(HttpResponseStatus.BAD_REQUEST + "\nMissing method",
                                  StandardCharsets.UTF_8).asReadOnly();
    private static final ByteBuf DATA_UNSUPPORTED_METHOD =
            Unpooled.copiedBuffer(HttpResponseStatus.METHOD_NOT_ALLOWED + "\nUnsupported method",
                                  StandardCharsets.UTF_8).asReadOnly();
    private static final ByteBuf DATA_INVALID_CONTENT_LENGTH =
            Unpooled.copiedBuffer(HttpResponseStatus.BAD_REQUEST + "\nInvalid content length",
                                  StandardCharsets.UTF_8).asReadOnly();

    private final ServerConfig cfg;
    private final Channel channel;
    private final Http2ConnectionEncoder writer;
    private final String scheme;

    private final InboundTrafficController inboundTrafficController;
    private final Http2GoAwayHandler goAwayHandler;
    private final IntObjectMap<DecodedHttpRequest> requests = new IntObjectHashMap<>();
    private int nextId;

    Http2RequestDecoder(ServerConfig cfg, Channel channel, Http2ConnectionEncoder writer, String scheme) {
        this.cfg = cfg;
        this.channel = channel;
        this.writer = writer;
        this.scheme = scheme;
        inboundTrafficController =
                InboundTrafficController.ofHttp2(channel, cfg.http2InitialConnectionWindowSize());
        goAwayHandler = new Http2GoAwayHandler();
    }

    Http2GoAwayHandler goAwayHandler() {
        return goAwayHandler;
    }

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
        ctx.fireChannelRead(settings);
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding,
                              boolean endOfStream) throws Http2Exception {
        DecodedHttpRequest req = requests.get(streamId);
        if (req == null) {
            // Validate the method.
            final CharSequence method = headers.method();
            if (method == null) {
                writeErrorResponse(ctx, streamId, HttpResponseStatus.BAD_REQUEST, DATA_MISSING_METHOD);
                return;
            }
            if (!HttpMethod.isSupported(method.toString())) {
                writeErrorResponse(ctx, streamId, HttpResponseStatus.METHOD_NOT_ALLOWED,
                                   DATA_UNSUPPORTED_METHOD);
                return;
            }

            // Validate the 'content-length' header if exists.
            final boolean contentEmpty;
            if (headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                final long contentLength = headers.getLong(HttpHeaderNames.CONTENT_LENGTH, -1L);
                if (contentLength < 0) {
                    writeErrorResponse(ctx, streamId, HttpResponseStatus.BAD_REQUEST,
                                       DATA_INVALID_CONTENT_LENGTH);
                    return;
                }
                contentEmpty = contentLength == 0;
            } else {
                contentEmpty = true;
            }

            if (!handle100Continue(ctx, streamId, headers)) {
                writeErrorResponse(ctx, streamId, HttpResponseStatus.EXPECTATION_FAILED, null);
                return;
            }

            req = new DecodedHttpRequest(ctx.channel().eventLoop(), ++nextId, streamId,
                                         ArmeriaHttpUtil.toArmeriaRequestHeaders(ctx, headers, endOfStream,
                                                                                 scheme, cfg),
                                         true, inboundTrafficController,
                                         // FIXME(trustin): Use a different maxRequestLength
                                         //                 for a different host.
                                         cfg.defaultVirtualHost().maxRequestLength());

            // Close the request early when it is sure that there will be
            // neither content nor trailers.
            if (contentEmpty && endOfStream) {
                req.close();
            }

            requests.put(streamId, req);
            ctx.fireChannelRead(req);
        } else {
            try {
                req.write(ArmeriaHttpUtil.toArmeria(headers, true, endOfStream));
            } catch (Throwable t) {
                req.close(t);
                throw connectionError(INTERNAL_ERROR, t, "failed to consume a HEADERS frame");
            }
        }

        if (endOfStream) {
            req.close();
        }
    }

    @Override
    public void onHeadersRead(
            ChannelHandlerContext ctx, int streamId, Http2Headers headers,
            int streamDependency, short weight, boolean exclusive, int padding,
            boolean endOfStream) throws Http2Exception {

        onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }

    private boolean handle100Continue(ChannelHandlerContext ctx, int streamId, Http2Headers headers) {
        final CharSequence expectValue = headers.get(HttpHeaderNames.EXPECT);
        if (expectValue == null) {
            // No 'expect' header.
            return true;
        }

        // '100-continue' is the only allowed expectation.
        if (!AsciiString.contentEqualsIgnoreCase(HttpHeaderValues.CONTINUE, expectValue)) {
            return false;
        }

        // Send a '100 Continue' response.
        writer.writeHeaders(
                ctx, streamId,
                new DefaultHttp2Headers(false).status(HttpStatus.CONTINUE.codeAsText()),
                0, false, ctx.voidPromise());

        // Remove the 'expect' header so that it's handled in a way invisible to a Service.
        headers.remove(HttpHeaderNames.EXPECT);
        return true;
    }

    @Override
    public void onStreamClosed(Http2Stream stream) {
        goAwayHandler.onStreamClosed(channel, stream);

        final DecodedHttpRequest req = requests.remove(stream.id());
        if (req != null) {
            // Ignored if the stream has already been closed.
            req.close(ClosedSessionException.get());
        }
    }

    @Override
    public int onDataRead(
            ChannelHandlerContext ctx, int streamId, ByteBuf data,
            int padding, boolean endOfStream) throws Http2Exception {

        final DecodedHttpRequest req = requests.get(streamId);
        if (req == null) {
            throw connectionError(PROTOCOL_ERROR, "received a DATA Frame for an unknown stream: %d",
                                  streamId);
        }

        final int dataLength = data.readableBytes();
        if (dataLength == 0) {
            // Received an empty DATA frame
            if (endOfStream) {
                req.close();
            }
            return padding;
        }

        req.increaseTransferredBytes(dataLength);

        final long maxContentLength = req.maxRequestLength();
        if (maxContentLength > 0 && req.transferredBytes() > maxContentLength) {
            final Http2Stream stream = writer.connection().stream(streamId);
            if (isWritable(stream)) {
                writeErrorResponse(ctx, streamId, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, null);
                writer.writeRstStream(ctx, streamId, Http2Error.CANCEL.code(), ctx.voidPromise());
                if (req.isOpen()) {
                    req.close(ContentTooLargeException.get());
                }
            } else {
                // The response has been started already. Abort the request and let the response continue.
                req.abort();
            }
        } else if (req.isOpen()) {
            try {
                req.write(new ByteBufHttpData(data.retain(), endOfStream));
            } catch (Throwable t) {
                req.close(t);
                throw connectionError(INTERNAL_ERROR, t, "failed to consume a DATA frame");
            }

            if (endOfStream) {
                req.close();
            }
        }

        // All bytes have been processed.
        return dataLength + padding;
    }

    private static boolean isWritable(Http2Stream stream) {
        switch (stream.state()) {
            case OPEN:
            case HALF_CLOSED_REMOTE:
                return !stream.isHeadersSent();
            default:
                return false;
        }
    }

    private void writeErrorResponse(ChannelHandlerContext ctx, int streamId, HttpResponseStatus status,
                                    @Nullable ByteBuf content) throws Http2Exception {
        final ByteBuf data =
                content != null ? content
                                : Unpooled.wrappedBuffer(status.toString().getBytes(StandardCharsets.UTF_8));

        writer.writeHeaders(
                ctx, streamId,
                new DefaultHttp2Headers(false)
                        .status(status.codeAsText())
                        .set(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8.toString())
                        .setInt(HttpHeaderNames.CONTENT_LENGTH, data.readableBytes()),
                0, false, ctx.voidPromise());

        writer.writeData(ctx, streamId, data, 0, true, ctx.voidPromise());

        final Http2Stream stream = writer.connection().stream(streamId);
        if (stream != null && writer.flowController().hasFlowControlled(stream)) {
            // Ensure to flush the error response if it's flow-controlled so that it is sent
            // before an RST_STREAM frame.
            writer.flowController().writePendingBytes();
        }
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
        final DecodedHttpRequest req = requests.get(streamId);
        if (req == null) {
            throw connectionError(PROTOCOL_ERROR,
                                  "received a RST_STREAM frame for an unknown stream: %d", streamId);
        }

        req.abortResponse(streamError(
                streamId, Http2Error.valueOf(errorCode), "received a RST_STREAM frame"));
    }

    @Override
    public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                  Http2Headers headers, int padding) throws Http2Exception {
        throw connectionError(PROTOCOL_ERROR, "received a PUSH_PROMISE frame which only a server can send");
    }

    @Override
    public void onGoAwaySent(int lastStreamId, long errorCode, ByteBuf debugData) {
        goAwayHandler.onGoAwaySent(channel, lastStreamId, errorCode, debugData);
    }

    @Override
    public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
        goAwayHandler.onGoAwayReceived(channel, lastStreamId, errorCode, debugData);
    }
}

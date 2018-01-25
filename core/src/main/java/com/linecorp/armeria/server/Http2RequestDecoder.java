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

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.ByteBufHttpData;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2EventAdapter;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;

final class Http2RequestDecoder extends Http2EventAdapter {

    private final ServerConfig cfg;
    private final Http2ConnectionEncoder writer;
    private final InboundTrafficController inboundTrafficController;
    private final IntObjectMap<DecodedHttpRequest> requests = new IntObjectHashMap<>();
    private int nextId;

    Http2RequestDecoder(ServerConfig cfg, Channel channel, Http2ConnectionEncoder writer) {
        this.cfg = cfg;
        this.writer = writer;
        inboundTrafficController = new InboundTrafficController(channel);
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
                writeErrorResponse(ctx, streamId, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            if (!HttpMethod.isSupported(method.toString())) {
                writeErrorResponse(ctx, streamId, HttpResponseStatus.METHOD_NOT_ALLOWED);
                return;
            }

            // Validate the 'content-length' header if exists.
            final boolean contentEmpty;
            if (headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                final long contentLength = headers.getLong(HttpHeaderNames.CONTENT_LENGTH, -1L);
                if (contentLength < 0) {
                    writeErrorResponse(ctx, streamId, HttpResponseStatus.BAD_REQUEST);
                    return;
                }
                contentEmpty = contentLength == 0;
            } else {
                contentEmpty = true;
            }

            req = new DecodedHttpRequest(ctx.channel().eventLoop(), ++nextId, streamId,
                                         ArmeriaHttpUtil.toArmeria(headers), true,
                                         inboundTrafficController, cfg.defaultMaxRequestLength());

            // Close the request early when it is sure that there will be
            // neither content nor trailing headers.
            if (contentEmpty && endOfStream) {
                req.close();
            }

            requests.put(streamId, req);
            ctx.fireChannelRead(req);
        } else {
            try {
                req.write(ArmeriaHttpUtil.toArmeria(headers));
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

    @Override
    public void onStreamRemoved(Http2Stream stream) {
        DecodedHttpRequest req = requests.remove(stream.id());
        if (req != null) {
            req.close();
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
            if (req.isOpen()) {
                req.close(ContentTooLargeException.get());
            }

            if (isWritable(streamId)) {
                writeErrorResponse(ctx, streamId, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
            } else {
                // Cannot write to the stream. Just close it.
                final Http2Stream stream = writer.connection().stream(streamId);
                stream.close();
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

    private boolean isWritable(int streamId) {
        switch (writer.connection().stream(streamId).state()) {
            case OPEN:
            case HALF_CLOSED_REMOTE:
                return true;
            default:
                return false;
        }
    }

    private void writeErrorResponse(ChannelHandlerContext ctx, int streamId, HttpResponseStatus status) {
        final byte[] content = status.toString().getBytes(StandardCharsets.UTF_8);

        writer.writeHeaders(
                ctx, streamId,
                new DefaultHttp2Headers(false)
                        .status(status.codeAsText())
                        .set(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8.toString())
                        .setInt(HttpHeaderNames.CONTENT_LENGTH, content.length),
                0, false, ctx.voidPromise());

        writer.writeData(
                ctx, streamId, Unpooled.wrappedBuffer(content), 0, true, ctx.voidPromise());
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
        final HttpRequestWriter req = requests.get(streamId);
        if (req == null) {
            throw connectionError(PROTOCOL_ERROR,
                                  "received a RST_STREAM frame for an unknown stream: %d", streamId);
        }

        req.close(streamError(
                streamId, Http2Error.valueOf(errorCode), "received a RST_STREAM frame"));
    }

    @Override
    public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                  Http2Headers headers, int padding) throws Http2Exception {
        throw connectionError(PROTOCOL_ERROR, "received a PUSH_PROMISE frame which only a server can send");
    }
}

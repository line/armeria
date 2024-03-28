/*
 * Copyright 2023 LINE Corporation
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

import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.common.InitiateConnectionShutdown;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.ReferenceCountUtil;

final class WebSocketServiceChannelHandler extends ChannelDuplexHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServiceChannelHandler.class);

    private final StreamingDecodedHttpRequest req;
    private final ServerHttpObjectEncoder encoder;
    private final ServiceConfig serviceConfig;

    WebSocketServiceChannelHandler(StreamingDecodedHttpRequest req, ServerHttpObjectEncoder encoder,
                                   ServiceConfig serviceConfig) {
        this.req = req;
        this.encoder = encoder;
        this.serviceConfig = serviceConfig;
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        req.close(ClosedSessionException.get());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof InitiateConnectionShutdown) {
            encoder.keepAliveHandler().disconnectWhenFinished();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof DecodedHttpRequest) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (msg == EMPTY_LAST_CONTENT) {
            // HttpServerCodec produces this after creating the headers. We can just ignore it.
            return;
        }
        if (!(msg instanceof ByteBuf)) {
            logger.warn("{} Unexpected msg: {}", ctx.channel(), msg);
            return;
        }
        encoder.keepAliveHandler().onReadOrWrite();
        try {
            final ByteBuf data = (ByteBuf) msg;
            final int dataLength = data.readableBytes();
            if (dataLength != 0) {
                req.increaseTransferredBytes(dataLength);
                final long maxContentLength = req.maxRequestLength();
                final long transferredLength = req.transferredBytes();
                if (maxContentLength > 0 && transferredLength > maxContentLength) {
                    final ContentTooLargeException cause =
                            ContentTooLargeException.builder()
                                                    .maxContentLength(maxContentLength)
                                                    .contentLength(req.headers())
                                                    .transferred(transferredLength)
                                                    .build();
                    if (encoder.isResponseHeadersSent(req.id(), 1)) {
                        encoder.writeReset(req.id(), 1, Http2Error.PROTOCOL_ERROR, false);
                    } else {
                        encoder.writeErrorResponse(req.id(), 1, serviceConfig, req.headers(),
                                                   HttpStatus.REQUEST_ENTITY_TOO_LARGE, null, null);
                    }
                    req.abortResponse(
                            HttpStatusException.of(HttpStatus.REQUEST_ENTITY_TOO_LARGE, cause),
                            true);
                    return;
                }

                if (req.isOpen()) {
                    req.write(HttpData.wrap(data.retain()));
                }
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse) {
            final HttpResponse response = (HttpResponse) msg;
            final HttpResponseStatus status = response.status();
            ctx.write(msg, promise);
            if (status.code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
                ctx.pipeline().remove(HttpServerCodec.class);
            }
            return;
        }

        if (msg instanceof HttpContent) {
            ctx.write(((HttpContent) msg).content(), promise);
            return;
        }
        ctx.write(msg, promise);
    }
}

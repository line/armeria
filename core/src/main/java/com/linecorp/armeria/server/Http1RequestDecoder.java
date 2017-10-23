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

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ProtocolViolationException;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeEvent;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

final class Http1RequestDecoder extends ChannelDuplexHandler {

    private static final Logger logger = LoggerFactory.getLogger(Http1RequestDecoder.class);

    private static final Http2Settings DEFAULT_HTTP2_SETTINGS = new Http2Settings();

    private final ServerConfig cfg;
    private final AsciiString scheme;
    private final InboundTrafficController inboundTrafficController;

    /** The request being decoded currently. */
    private DecodedHttpRequest req;
    private int receivedRequests;
    private int sentResponses;
    private boolean discarding;

    Http1RequestDecoder(ServerConfig cfg, Channel channel, AsciiString scheme) {
        this.cfg = cfg;
        this.scheme = scheme;
        inboundTrafficController = new InboundTrafficController(channel);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpObject)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // this.req can be set to null by fail(), so we keep it in a local variable.
        DecodedHttpRequest req = this.req;
        try {
            if (discarding) {
                return;
            }

            if (req == null) {
                if (msg instanceof HttpRequest) {
                    final HttpRequest nettyReq = (HttpRequest) msg;
                    if (!nettyReq.decoderResult().isSuccess()) {
                        fail(ctx, HttpResponseStatus.BAD_REQUEST);
                        return;
                    }

                    final HttpHeaders nettyHeaders = nettyReq.headers();
                    final int id = ++receivedRequests;

                    // Validate the method.
                    if (!HttpMethod.isSupported(nettyReq.method().name())) {
                        fail(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
                        return;
                    }

                    // Validate the 'content-length' header.
                    final String contentLengthStr = nettyHeaders.get(HttpHeaderNames.CONTENT_LENGTH);
                    if (contentLengthStr != null) {
                        final long contentLength;
                        try {
                            contentLength = Long.parseLong(contentLengthStr);
                        } catch (NumberFormatException ignored) {
                            fail(ctx, HttpResponseStatus.BAD_REQUEST);
                            return;
                        }
                        if (contentLength < 0) {
                            fail(ctx, HttpResponseStatus.BAD_REQUEST);
                            return;
                        }
                    }

                    nettyHeaders.set(ExtensionHeaderNames.SCHEME.text(), scheme);

                    this.req = req = new DecodedHttpRequest(
                            ctx.channel().eventLoop(),
                            id, 1,
                            ArmeriaHttpUtil.toArmeria(nettyReq),
                            HttpUtil.isKeepAlive(nettyReq),
                            inboundTrafficController,
                            cfg.defaultMaxRequestLength());

                    ctx.fireChannelRead(req);
                } else {
                    fail(ctx, HttpResponseStatus.BAD_REQUEST);
                    return;
                }
            }

            if (req != null && msg instanceof HttpContent) {
                final HttpContent content = (HttpContent) msg;
                final DecoderResult decoderResult = content.decoderResult();
                if (!decoderResult.isSuccess()) {
                    fail(ctx, HttpResponseStatus.BAD_REQUEST);
                    req.close(new ProtocolViolationException(decoderResult.cause()));
                    return;
                }

                final ByteBuf data = content.content();
                final int dataLength = data.readableBytes();
                if (dataLength != 0) {
                    req.increaseTransferredBytes(dataLength);
                    final long maxContentLength = req.maxRequestLength();
                    if (maxContentLength > 0 && req.transferredBytes() > maxContentLength) {
                        fail(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
                        req.close(ContentTooLargeException.get());
                        return;
                    }

                    if (req.isOpen()) {
                        req.write(HttpData.of(data));
                    }
                }

                if (msg instanceof LastHttpContent) {
                    final HttpHeaders trailingHeaders = ((LastHttpContent) msg).trailingHeaders();
                    if (!trailingHeaders.isEmpty()) {
                        req.write(ArmeriaHttpUtil.toArmeria(trailingHeaders));
                    }

                    req.close();
                    this.req = req = null;
                }
            }
        } catch (URISyntaxException e) {
            fail(ctx, HttpResponseStatus.BAD_REQUEST);
            if (req != null) {
                req.close(e);
            }
        } catch (Throwable t) {
            fail(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
            if (req != null) {
                req.close(t);
            } else {
                logger.warn("Unexpected exception:", t);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void fail(ChannelHandlerContext ctx, HttpResponseStatus status) {
        discarding = true;
        req = null;

        final ChannelFuture future;
        if (receivedRequests <= sentResponses) {
            // Just close the connection if sending an error response will make the number of the sent
            // responses exceed the number of the received requests, which doesn't make sense.
            future = ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
        } else {
            final ByteBuf content = Unpooled.copiedBuffer(status.toString(), StandardCharsets.UTF_8);
            final FullHttpResponse res = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, content);

            final HttpHeaders headers = res.headers();
            headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            headers.set(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
            headers.setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());

            future = ctx.writeAndFlush(res);
        }

        future.addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof UpgradeEvent) {
            // Generate the initial Http2Settings frame,
            // so that the next handler knows the protocol upgrade occurred as well.
            ctx.fireChannelRead(DEFAULT_HTTP2_SETTINGS);

            // Continue handling the upgrade request after the upgrade is complete.
            final FullHttpRequest nettyReq = ((UpgradeEvent) evt).upgradeRequest();

            // Remove the headers related with the upgrade.
            nettyReq.headers().remove(HttpHeaderNames.CONNECTION);
            nettyReq.headers().remove(HttpHeaderNames.UPGRADE);
            nettyReq.headers().remove(Http2CodecUtil.HTTP_UPGRADE_SETTINGS_HEADER);

            if (logger.isDebugEnabled()) {
                logger.debug("{} Handling the pre-upgrade request ({}): {} {} {} ({}B)",
                             ctx.channel(), ((UpgradeEvent) evt).protocol(),
                             nettyReq.method(), nettyReq.uri(), nettyReq.protocolVersion(),
                             nettyReq.content().readableBytes());
            }

            channelRead(ctx, nettyReq);
            channelReadComplete(ctx);
            return;
        }

        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse &&
            ((HttpResponse) msg).status().codeClass() != HttpStatusClass.INFORMATIONAL) {
            sentResponses++;
        }
        ctx.write(msg, promise);
    }
}

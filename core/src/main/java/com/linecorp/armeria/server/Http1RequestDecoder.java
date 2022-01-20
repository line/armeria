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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ProtocolViolationException;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.InitiateConnectionShutdown;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;
import com.linecorp.armeria.server.HttpServerUpgradeHandler.UpgradeEvent;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpExpectationFailedEvent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

final class Http1RequestDecoder extends ChannelDuplexHandler {

    private static final Logger logger = LoggerFactory.getLogger(Http1RequestDecoder.class);

    private static final Http2Settings DEFAULT_HTTP2_SETTINGS = new Http2Settings();
    private static final ResponseHeaders CONTINUE_RESPONSE = ResponseHeaders.of(HttpStatus.CONTINUE);

    private final ServerConfig cfg;
    private final AsciiString scheme;
    private final InboundTrafficController inboundTrafficController;
    private ServerHttpObjectEncoder encoder;

    /** The request being decoded currently. */
    @Nullable
    private DecodedHttpRequest req;
    private int receivedRequests;
    private boolean discarding;

    Http1RequestDecoder(ServerConfig cfg, Channel channel, AsciiString scheme,
                        ServerHttp1ObjectEncoder encoder) {
        this.cfg = cfg;
        this.scheme = scheme;
        inboundTrafficController = InboundTrafficController.ofHttp1(channel);
        this.encoder = encoder;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        maybeInitializeKeepAliveHandler(ctx);
        super.handlerAdded(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        destroyKeepAliveHandler();
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        maybeInitializeKeepAliveHandler(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        if (req instanceof HttpRequestWriter) {
            // Ignored if the stream has already been closed.
            ((HttpRequestWriter) req).close(ClosedSessionException.get());
        }
        destroyKeepAliveHandler();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        destroyKeepAliveHandler();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpObject)) {
            ctx.fireChannelRead(msg);
            return;
        }

        final KeepAliveHandler keepAliveHandler = encoder.keepAliveHandler();
        keepAliveHandler.onReadOrWrite();
        // this.req can be set to null by fail(), so we keep it in a local variable.
        DecodedHttpRequest req = this.req;
        final int id = req != null ? req.id() : ++receivedRequests;
        try {
            if (discarding) {
                return;
            }

            if (req == null) {
                if (msg instanceof HttpRequest) {
                    keepAliveHandler.increaseNumRequests();
                    final HttpRequest nettyReq = (HttpRequest) msg;
                    if (!nettyReq.decoderResult().isSuccess()) {
                        fail(id, null, HttpStatus.BAD_REQUEST, "Decoder failure", null);
                        return;
                    }

                    // Do not accept unsupported methods.
                    final io.netty.handler.codec.http.HttpMethod nettyMethod = nettyReq.method();
                    if (!HttpMethod.isSupported(nettyMethod.name())) {
                        fail(id, null, HttpStatus.METHOD_NOT_ALLOWED, "Unsupported method", null);
                        return;
                    }

                    // Handle `expect: 100-continue` first to give `handle100Continue()` a chance to remove
                    // the `expect` header before converting the Netty HttpHeaders into Armeria RequestHeaders.
                    // This is because removing a header from RequestHeaders is more expensive due to its
                    // immutability.
                    final boolean hasInvalidExpectHeader = !handle100Continue(id, nettyReq);

                    // Convert the Netty HttpHeaders into Armeria RequestHeaders.
                    final RequestHeaders headers =
                            ArmeriaHttpUtil.toArmeria(ctx, nettyReq, cfg, scheme.toString());

                    // Do not accept a CONNECT request.
                    if (headers.method() == HttpMethod.CONNECT) {
                        fail(id, headers, HttpStatus.METHOD_NOT_ALLOWED, "Unsupported method", null);
                        return;
                    }

                    // Validate the 'content-length' header.
                    final String contentLengthStr = headers.get(HttpHeaderNames.CONTENT_LENGTH);
                    final boolean contentEmpty;
                    if (contentLengthStr != null) {
                        long contentLength;
                        try {
                            contentLength = Long.parseLong(contentLengthStr);
                        } catch (NumberFormatException ignored) {
                            contentLength = -1;
                        }
                        if (contentLength < 0) {
                            fail(id, headers, HttpStatus.BAD_REQUEST, "Invalid content length", null);
                            return;
                        }

                        contentEmpty = contentLength == 0;
                    } else {
                        contentEmpty = true;
                    }

                    // Reject the requests with an `expect` header whose value is not `100-continue`.
                    if (hasInvalidExpectHeader) {
                        ctx.pipeline().fireUserEventTriggered(HttpExpectationFailedEvent.INSTANCE);
                        fail(id, headers, HttpStatus.EXPECTATION_FAILED, null, null);
                        return;
                    }

                    // Close the request early when it is certain there will be neither content nor trailers.
                    final EventLoop eventLoop = ctx.channel().eventLoop();
                    final boolean keepAlive = HttpUtil.isKeepAlive(nettyReq);
                    if (contentEmpty && !HttpUtil.isTransferEncodingChunked(nettyReq)) {
                        this.req = req = new EmptyContentDecodedHttpRequest(
                                eventLoop, id, 1, headers, keepAlive);
                    } else {
                        this.req = req = new DefaultDecodedHttpRequest(
                                eventLoop, id, 1, headers, keepAlive, inboundTrafficController,
                                // FIXME(trustin): Use a different maxRequestLength for a different virtual
                                //                 host.
                                cfg.defaultVirtualHost().maxRequestLength());
                    }

                    ctx.fireChannelRead(req);
                } else {
                    fail(id, null, HttpStatus.BAD_REQUEST, "Invalid decoder state", null);
                    return;
                }
            }
            if (msg instanceof LastHttpContent && encoder instanceof ServerHttp2ObjectEncoder) {
                // An HTTP/1 connection has been upgraded to HTTP/2.
                ctx.pipeline().remove(this);
            }

            // req is not null.
            if (msg instanceof LastHttpContent && req instanceof EmptyContentDecodedHttpRequest) {
                this.req = null;
            } else if (msg instanceof HttpContent) {
                assert req instanceof DefaultDecodedHttpRequest;
                final DefaultDecodedHttpRequest decodedReq = (DefaultDecodedHttpRequest) req;
                final HttpContent content = (HttpContent) msg;
                final DecoderResult decoderResult = content.decoderResult();
                if (!decoderResult.isSuccess()) {
                    fail(id, decodedReq.headers(), HttpStatus.BAD_REQUEST,
                         Http2Error.PROTOCOL_ERROR, "Decoder failure", null);
                    final ProtocolViolationException cause =
                            new ProtocolViolationException(decoderResult.cause());
                    decodedReq.close(HttpStatusException.of(HttpStatus.BAD_REQUEST, cause));
                    return;
                }

                final ByteBuf data = content.content();
                final int dataLength = data.readableBytes();
                if (dataLength != 0) {
                    decodedReq.increaseTransferredBytes(dataLength);
                    final long maxContentLength = decodedReq.maxRequestLength();
                    final long transferredLength = decodedReq.transferredBytes();
                    if (maxContentLength > 0 && transferredLength > maxContentLength) {
                        final ContentTooLargeException cause =
                                ContentTooLargeException.builder()
                                                        .maxContentLength(maxContentLength)
                                                        .contentLength(req.headers())
                                                        .transferred(transferredLength)
                                                        .build();
                        fail(id, decodedReq.headers(), HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                             Http2Error.CANCEL, "Request entity too large", cause);
                        // Wrap the cause with the returned status to let LoggingService correctly log the
                        // status.
                        decodedReq.close(HttpStatusException.of(HttpStatus.REQUEST_ENTITY_TOO_LARGE, cause));
                        return;
                    }

                    if (decodedReq.isOpen()) {
                        decodedReq.write(HttpData.wrap(data.retain()));
                    }
                }

                if (msg instanceof LastHttpContent) {
                    final HttpHeaders trailingHeaders = ((LastHttpContent) msg).trailingHeaders();
                    if (!trailingHeaders.isEmpty()) {
                        decodedReq.write(ArmeriaHttpUtil.toArmeria(trailingHeaders));
                    }

                    decodedReq.close();
                    this.req = null;
                }
            }
        } catch (URISyntaxException e) {
            if (req != null) {
                fail(id, req.headers(), HttpStatus.BAD_REQUEST, Http2Error.CANCEL, "Invalid request path", e);
                req.close(HttpStatusException.of(HttpStatus.BAD_REQUEST, e));
            } else {
                fail(id, null, HttpStatus.BAD_REQUEST, Http2Error.CANCEL, "Invalid request path", e);
            }
        } catch (Throwable t) {
            if (req != null) {
                fail(id, req.headers(), HttpStatus.INTERNAL_SERVER_ERROR, Http2Error.INTERNAL_ERROR, null, t);
                req.close(HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR, t));
            } else {
                fail(id, null, HttpStatus.INTERNAL_SERVER_ERROR, Http2Error.INTERNAL_ERROR, null, t);
                logger.warn("Unexpected exception:", t);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private boolean handle100Continue(int id, HttpRequest nettyReq) {
        final HttpHeaders nettyHeaders = nettyReq.headers();
        if (nettyReq.protocolVersion().compareTo(HttpVersion.HTTP_1_1) < 0) {
            // Ignore HTTP/1.0 requests.
            return true;
        }

        final String expectValue = nettyHeaders.get(HttpHeaderNames.EXPECT);
        if (expectValue == null) {
            // No 'expect' header.
            return true;
        }

        // '100-continue' is the only allowed expectation.
        if (!Ascii.equalsIgnoreCase("100-continue", expectValue)) {
            return false;
        }

        // Send a '100 Continue' response.
        encoder.writeHeaders(id, 1, CONTINUE_RESPONSE, false);

        // Remove the 'expect' header so that it's handled in a way invisible to a Service.
        nettyHeaders.remove(HttpHeaderNames.EXPECT);
        return true;
    }

    private void fail(int id, @Nullable RequestHeaders headers, HttpStatus status, Http2Error error,
                      @Nullable String message, @Nullable Throwable cause) {
        if (encoder.isResponseHeadersSent(id, 1)) {
            // The response is sent or being sent by HttpResponseSubscriber so we cannot send
            // the error response.
            encoder.writeReset(id, 1, error);
        } else {
            fail(id, headers, status, message, cause);
        }
    }

    private void fail(int id, @Nullable RequestHeaders headers, HttpStatus status,
                      @Nullable String message, @Nullable Throwable cause) {
        discarding = true;
        req = null;

        // FIXME(trustin): Use a different verboseResponses for a different virtual host.
        encoder.writeErrorResponse(id, 1, cfg.defaultVirtualHost().fallbackServiceConfig(),
                                   headers, status, message, cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof UpgradeEvent) {

            final ChannelPipeline pipeline = ctx.pipeline();
            final ChannelHandlerContext connectionHandlerCtx =
                    pipeline.context(Http2ServerConnectionHandler.class);
            final Http2ServerConnectionHandler connectionHandler =
                    (Http2ServerConnectionHandler) connectionHandlerCtx.handler();
            encoder.close();
            // The HTTP/2 encoder will be used when a protocol violation error occurs after upgrading to HTTP/2
            // that is directly written by 'fail()'.
            encoder = connectionHandler.getOrCreateResponseEncoder(connectionHandlerCtx);

            // Generate the initial Http2Settings frame,
            // so that the next handler knows the protocol upgrade occurred as well.
            ctx.fireChannelRead(DEFAULT_HTTP2_SETTINGS);

            // Continue handling the upgrade request after the upgrade is complete.
            final HttpRequest nettyReq = ((UpgradeEvent) evt).upgradeRequest();

            // Remove the headers related with the upgrade.
            nettyReq.headers().remove(HttpHeaderNames.CONNECTION);
            nettyReq.headers().remove(HttpHeaderNames.UPGRADE);
            nettyReq.headers().remove(Http2CodecUtil.HTTP_UPGRADE_SETTINGS_HEADER);

            if (logger.isDebugEnabled()) {
                logger.debug("{} Handling the pre-upgrade request ({}): {} {} {}",
                             ctx.channel(), ((UpgradeEvent) evt).protocol(),
                             nettyReq.method(), nettyReq.uri(), nettyReq.protocolVersion());
            }

            channelRead(ctx, nettyReq);
            return;
        }
        if (evt instanceof InitiateConnectionShutdown && encoder instanceof ServerHttp1ObjectEncoder) {
            // HTTP/1 doesn't support draining that signals clients about connection shutdown but still
            // accepts in flight requests. Simply destroy KeepAliveHandler which causes next response
            // to have a "Connection: close" header and connection to be closed after the next response.
            destroyKeepAliveHandler();
            ((ServerHttp1ObjectEncoder) encoder).initiateConnectionShutdown();
            return;
        }

        ctx.fireUserEventTriggered(evt);
    }

    private void maybeInitializeKeepAliveHandler(ChannelHandlerContext ctx) {
        final KeepAliveHandler keepAliveHandler = encoder.keepAliveHandler();
        if (keepAliveHandler != NoopKeepAliveHandler.INSTANCE &&
            ctx.channel().isActive() && ctx.channel().isRegistered()) {
            keepAliveHandler.initialize(ctx);
        }
    }

    private void destroyKeepAliveHandler() {
        if (encoder instanceof Http1ObjectEncoder) {
            // Http2ObjectEncoder will be destroyed by Http2RequestDecoder.
            encoder.keepAliveHandler().destroy();
        }
    }
}

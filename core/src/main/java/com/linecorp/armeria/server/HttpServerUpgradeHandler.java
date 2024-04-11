/*
 * Copyright 2021 LINE Corporation
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
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linecorp.armeria.server;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.netty.channel.ChannelFutureListener.CLOSE;
import static io.netty.channel.ChannelFutureListener.CLOSE_ON_FAILURE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.util.AsciiString.containsContentEqualsIgnoreCase;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.common.base.Splitter;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

/**
 * A server-side handler that receives HTTP requests and optionally performs a protocol switch if
 * the requested protocol is supported. Once an upgrade is performed, this handler removes itself
 * from the pipeline.
 */
final class HttpServerUpgradeHandler extends ChannelInboundHandlerAdapter {

    // Forked from http://github.com/netty/netty/blob/cf624c93c5f97097f1b13fe926ed50c32c8b1430/codec-http/src/main/java/io/netty/handler/codec/http/HttpServerUpgradeHandler.java
    // The upstream HttpServerUpgradeHandler fully aggregates an HTTP request. As a result, the upgrade handler
    // cannot handle an upgrade request whose body is lager than the maximum length of the content specified
    // when creating the upgrade handler. The forked HttpServerUpgradeHandler removed HttpObjectAggregator from
    // superclass and only use HTTP headers to perform h2c upgrade so that it upgrades a request without
    // limitation of the size of content.

    private static final FullHttpResponse UPGRADE_RESPONSE = newUpgradeResponse();

    private static final Splitter COMMA_SPLITTER = Splitter.on(',').omitEmptyStrings().trimResults();

    private static final FullHttpResponse invalidSettingsHeaderResponse =
            new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST, Unpooled.unreleasableBuffer(
                    Unpooled.directBuffer()
                            .writeBytes("Invalid HTTP2-Settings header\n".getBytes(StandardCharsets.UTF_8))));

    /**
     * A codec that the source can be upgraded to {@link SessionProtocol#H2C}.
     */
    interface UpgradeCodec {
        /**
         * Prepares the {@code upgradeHeaders} for a protocol update based upon the contents of
         * {@code upgradeRequest}.
         * This method returns a boolean value to proceed or abort the upgrade in progress. If {@code false} is
         * returned, the upgrade is aborted and the {@code upgradeRequest} will be passed through the inbound
         * pipeline as if no upgrade was performed. If {@code true} is returned, the upgrade will proceed to
         * the next step which invokes {@link #upgradeTo}.
         */
        boolean prepareUpgradeResponse(ChannelHandlerContext ctx, HttpRequest upgradeRequest);

        /**
         * Performs an HTTP protocol upgrade from the source codec. This method is responsible for
         * adding all handlers required for the new protocol.
         *
         * @param ctx the context for the current handler.
         */
        void upgradeTo(ChannelHandlerContext ctx);
    }

    /**
     * Creates a new {@link UpgradeCodec} for {@link SessionProtocol#H2C} upgrade.
     */
    @FunctionalInterface
    interface UpgradeCodecFactory {
        /**
         * Invoked by {@link HttpServerUpgradeHandler} for {@link SessionProtocol#H2C}.
         */
        UpgradeCodec newUpgradeCodec();
    }

    /**
     * User event that is fired to notify about the completion of an HTTP upgrade
     * to another protocol. Contains the original upgrade request so that the response
     * (if required) can be sent using the new protocol.
     */
    static final class UpgradeEvent implements ReferenceCounted {
        private final HttpRequest upgradeRequest;

        UpgradeEvent(HttpRequest upgradeRequest) {
            this.upgradeRequest = upgradeRequest;
        }

        /**
         * The protocol that the channel has been upgraded to.
         */
        public CharSequence protocol() {
            return Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME;
        }

        /**
         * Gets the request that triggered the protocol upgrade.
         */
        public HttpRequest upgradeRequest() {
            return upgradeRequest;
        }

        @Override
        public int refCnt() {
            return ReferenceCountUtil.refCnt(upgradeRequest);
        }

        @Override
        public UpgradeEvent retain() {
            ReferenceCountUtil.retain(upgradeRequest);
            return this;
        }

        @Override
        public UpgradeEvent retain(int increment) {
            ReferenceCountUtil.retain(upgradeRequest);
            return this;
        }

        @Override
        public UpgradeEvent touch() {
            ReferenceCountUtil.touch(upgradeRequest);
            return this;
        }

        @Override
        public UpgradeEvent touch(Object hint) {
            ReferenceCountUtil.touch(upgradeRequest, hint);
            return this;
        }

        @Override
        public boolean release() {
            return ReferenceCountUtil.release(upgradeRequest);
        }

        @Override
        public boolean release(int decrement) {
            return ReferenceCountUtil.release(upgradeRequest, decrement);
        }

        @Override
        public String toString() {
            return "UpgradeEvent [protocol=" + protocol() + ", upgradeRequest=" + upgradeRequest + ']';
        }
    }

    private final HttpServerCodec sourceCodec;
    private final UpgradeCodecFactory upgradeCodecFactory;

    @Nullable
    private UpgradeCodec upgradeCodec;
    private boolean handlingUpgrade;
    private boolean handlingInvalidSettingsHeader;

    /**
     * Constructs the upgrader with the supported codecs.
     *
     * @param sourceCodec the codec that is being used initially
     * @param upgradeCodecFactory the factory that creates a new upgrade codec
     *                            for one of the requested upgrade protocols
     */
    HttpServerUpgradeHandler(
            HttpServerCodec sourceCodec, UpgradeCodecFactory upgradeCodecFactory) {
        this.sourceCodec = checkNotNull(sourceCodec, "sourceCodec");
        this.upgradeCodecFactory = checkNotNull(upgradeCodecFactory, "upgradeCodecFactory");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Not handling an upgrade request yet. Check if we received a new upgrade request.
        if (msg instanceof HttpRequest) {
            final HttpRequest req = (HttpRequest) msg;
            if (req.headers().contains(HttpHeaderNames.UPGRADE) && upgrade(ctx, req)) {
                handlingUpgrade = true;
                return;
            }
            if (handlingInvalidSettingsHeader) {
                return;
            }
        }

        ctx.fireChannelRead(msg);

        if (handlingUpgrade && msg instanceof LastHttpContent) {
            // The client should send a full payload body before sending HTTP/2 frames.
            // Hence, 'sourceCodec' could be lazily removed with the 'LastHttpContent'.
            // https://datatracker.ietf.org/doc/html/rfc7540#section-3.2
            sourceCodec.upgradeFrom(ctx);
            ctx.fireChannelReadComplete();
            ctx.pipeline().remove(this);
        }
    }

    /**
     * Attempts to upgrade to the protocol(s) identified by the {@link HttpHeaderNames#UPGRADE} header
     * (if provided in the request).
     *
     * @param ctx the context for this handler.
     * @param request the HTTP request.
     * @return {@code true} if the upgrade occurred, otherwise {@code false}.
     */
    private boolean upgrade(ChannelHandlerContext ctx, HttpRequest request) {
        // Select the best protocol based on those requested in the UPGRADE header.
        final List<String> requestedProtocols =
                COMMA_SPLITTER.splitToList(request.headers().get(HttpHeaderNames.UPGRADE));
        final int numRequestedProtocols = requestedProtocols.size();
        for (int i = 0; i < numRequestedProtocols; i++) {
            final CharSequence protocol = requestedProtocols.get(i);
            if (!AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                continue;
            }
            upgradeCodec = upgradeCodecFactory.newUpgradeCodec();
            break;
        }

        if (upgradeCodec == null) {
            // None of the requested protocols are supported, don't upgrade.
            return false;
        }

        // Make sure the CONNECTION header is present.
        final List<String> connectionHeaderValues = request.headers().getAll(HttpHeaderNames.CONNECTION);

        if (connectionHeaderValues == null || connectionHeaderValues.isEmpty()) {
            return false;
        }

        final List<CharSequence> values = connectionHeaderValues.stream()
                                                                .flatMap(COMMA_SPLITTER::splitToStream)
                                                                .collect(toImmutableList());
        // Make sure the CONNECTION header contains UPGRADE as well as all protocol-specific headers.
        if (!(containsContentEqualsIgnoreCase(values, HttpHeaderNames.UPGRADE) &&
              containsContentEqualsIgnoreCase(values, Http2CodecUtil.HTTP_UPGRADE_SETTINGS_HEADER))) {
            return false;
        }

        // Ensure that HTTP2-Settings headers are found in the request.
        if (!request.headers().contains(Http2CodecUtil.HTTP_UPGRADE_SETTINGS_HEADER)) {
            return false;
        }

        // Prepare and send the upgrade response. Wait for this write to complete before upgrading,
        // since we need the old codec in-place to properly encode the response.
        if (!upgradeCodec.prepareUpgradeResponse(ctx, request)) {
            ctx.writeAndFlush(invalidSettingsHeaderResponse.duplicate()).addListener(CLOSE);
            handlingInvalidSettingsHeader = true;
            return false;
        }

        // Create the user event to be fired once the upgrade completes.
        final UpgradeEvent event = new UpgradeEvent(request);

        // After writing the upgrade response we immediately prepare the
        // pipeline for the next protocol to avoid a race between completion
        // of the write future and receiving data before the pipeline is
        // restructured.
        try {
            final ChannelFuture writeComplete = ctx.writeAndFlush(UPGRADE_RESPONSE.retain());

            // The request could not be fully received yet.
            // The HTTP/1 codec will be completely removed after receiving the entire request.
            sourceCodec.removeOutboundHandler();

            // Perform the upgrade to the new protocol.
            assert upgradeCodec != null;
            upgradeCodec.upgradeTo(ctx);

            // Notify that the upgrade has occurred. Retain the event to offset
            // the release() in the finally block.
            ctx.fireUserEventTriggered(event.retain());

            // Add the listener last to avoid firing upgrade logic after
            // the channel is already closed since the listener may fire
            // immediately if the write failed eagerly.
            writeComplete.addListener(CLOSE_ON_FAILURE);
        } finally {
            // Release the event if the upgrade event wasn't fired.
            event.release();
        }
        return true;
    }

    /**
     * Creates the 101 Switching Protocols response message.
     */
    private static FullHttpResponse newUpgradeResponse() {
        final DefaultFullHttpResponse res = new DefaultFullHttpResponse(
                HTTP_1_1, SWITCHING_PROTOCOLS, Unpooled.EMPTY_BUFFER, /* validateHeaders */ true);
        res.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        res.headers().add(HttpHeaderNames.UPGRADE, Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME);
        return res;
    }
}

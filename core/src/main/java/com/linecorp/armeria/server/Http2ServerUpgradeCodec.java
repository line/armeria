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

import static io.netty.handler.codec.base64.Base64Dialect.URL_SAFE;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.HTTP_UPGRADE_SETTINGS_HEADER;
import static io.netty.handler.codec.http2.Http2CodecUtil.writeFrameHeader;
import static io.netty.handler.codec.http2.Http2FrameTypes.SETTINGS;

import java.nio.CharBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.CharsetUtil;

/**
 * Server-side codec for performing a cleartext upgrade from HTTP/1.x to HTTP/2.
 */
final class Http2ServerUpgradeCodec implements HttpServerUpgradeHandler.UpgradeCodec {

    // Forked from http://github.com/netty/netty/blob/cf624c93c5f97097f1b13fe926ed50c32c8b1430/codec-http2/src/main/java/io/netty/handler/codec/http2/Http2ServerUpgradeCodec.java

    private static final Logger logger = LoggerFactory.getLogger(Http2ServerUpgradeCodec.class);
    private static final List<CharSequence> REQUIRED_UPGRADE_HEADERS =
            Collections.singletonList(HTTP_UPGRADE_SETTINGS_HEADER);
    private static final ChannelHandler[] EMPTY_HANDLERS = new ChannelHandler[0];

    @Nullable
    private final String handlerName;
    private final Http2ConnectionHandler connectionHandler;
    private final ChannelHandler[] handlers;
    private final Http2FrameReader frameReader;

    @Nullable
    private Http2Settings settings;

    /**
     * Creates the codec using a default name for the connection handler when adding to the
     * pipeline.
     *
     * @param connectionHandler the HTTP/2 connection handler
     */
    Http2ServerUpgradeCodec(Http2ConnectionHandler connectionHandler) {
        handlerName = null;
        this.connectionHandler = connectionHandler;
        handlers = EMPTY_HANDLERS;
        frameReader = new DefaultHttp2FrameReader();
    }

    @Override
    public boolean prepareUpgradeResponse(ChannelHandlerContext ctx, HttpRequest upgradeRequest) {
        try {
            // Decode the HTTP2-Settings header and set the settings on the handler to make
            // sure everything is fine with the request.
            final List<String> upgradeHeaders = upgradeRequest.headers().getAll(HTTP_UPGRADE_SETTINGS_HEADER);
            if (upgradeHeaders.size() != 1) {
                throw new IllegalArgumentException(
                        "There must be 1 and only 1 " + HTTP_UPGRADE_SETTINGS_HEADER + " header.");
            }
            settings = decodeSettingsHeader(ctx, upgradeHeaders.get(0));
            // Everything looks good.
            return true;
        } catch (Throwable cause) {
            logger.info("Error during upgrade to HTTP/2", cause);
            return false;
        }
    }

    @Override
    public void upgradeTo(ChannelHandlerContext ctx) {
        try {
            // Add the HTTP/2 connection handler to the pipeline immediately following the current handler.
            ctx.pipeline().addAfter(ctx.name(), handlerName, connectionHandler);

            // Add also all extra handlers as these may handle events / messages produced by the
            // connectionHandler. See https://github.com/netty/netty/issues/9314
            if (handlers != null) {
                final String name = ctx.pipeline().context(connectionHandler).name();
                for (int i = handlers.length - 1; i >= 0; i--) {
                    ctx.pipeline().addAfter(name, null, handlers[i]);
                }
            }
            connectionHandler.onHttpServerUpgrade(settings);
        } catch (Http2Exception e) {
            ctx.fireExceptionCaught(e);
            ctx.close();
        }
    }

    /**
     * Decodes the settings header and returns a {@link Http2Settings} object.
     */
    private Http2Settings decodeSettingsHeader(ChannelHandlerContext ctx, CharSequence settingsHeader)
            throws Http2Exception {
        final ByteBuf header = ByteBufUtil.encodeString(ctx.alloc(),
                                                        CharBuffer.wrap(settingsHeader), CharsetUtil.UTF_8);
        try {
            // Decode the SETTINGS payload.
            final ByteBuf payload = Base64.decode(header, URL_SAFE);

            // Create an HTTP/2 frame for the settings.
            final ByteBuf frame = createSettingsFrame(ctx, payload);

            // Decode the SETTINGS frame and return the settings object.
            return decodeSettings(ctx, frame);
        } finally {
            header.release();
        }
    }

    /**
     * Decodes the settings frame and returns the settings.
     */
    private Http2Settings decodeSettings(ChannelHandlerContext ctx, ByteBuf frame) throws Http2Exception {
        try {
            final Http2Settings decodedSettings = new Http2Settings();
            frameReader.readFrame(ctx, frame, new Http2FrameAdapter() {
                @Override
                public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
                    decodedSettings.copyFrom(settings);
                }
            });
            return decodedSettings;
        } finally {
            frame.release();
        }
    }

    /**
     * Creates an HTTP2-Settings header with the given payload. The payload buffer is released.
     */
    private static ByteBuf createSettingsFrame(ChannelHandlerContext ctx, ByteBuf payload) {
        final ByteBuf frame = ctx.alloc().buffer(FRAME_HEADER_LENGTH + payload.readableBytes());
        writeFrameHeader(frame, payload.readableBytes(), SETTINGS, new Http2Flags(), 0);
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }
}

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
 * Copyright 2018 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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

import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpServerUpgradeHandler.UpgradeCodec;
import com.linecorp.armeria.server.HttpServerUpgradeHandler.UpgradeCodecFactory;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

class HttpServerUpgradeHandlerTest {

    // Forked from http://github.com/netty/netty/blob/cf624c93c5f97097f1b13fe926ed50c32c8b1430/codec-http/src/test/java/io/netty/handler/codec/http/HttpServerUpgradeHandlerTest.java

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(OK));
        }
    };

    private static class TestUpgradeCodec implements UpgradeCodec {
        @Override
        public boolean prepareUpgradeResponse(ChannelHandlerContext ctx, HttpRequest upgradeRequest) {
            return true;
        }

        @Override
        public void upgradeTo(ChannelHandlerContext ctx) {
            // Ensure that the HttpServerUpgradeHandler is still installed when this is called
            assertEquals(ctx.pipeline().context(HttpServerUpgradeHandler.class), ctx);
            assertNotNull(ctx.pipeline().get(HttpServerUpgradeHandler.class));

            // Add a marker handler to signal that the upgrade has happened
            ctx.pipeline().addAfter(ctx.name(), "marker", new ChannelInboundHandlerAdapter());
        }
    }

    @Test
    void upgradesPipelineInSameMethodInvocation() {
        final HttpServerCodec httpServerCodec = new HttpServerCodec();
        final UpgradeCodecFactory factory = TestUpgradeCodec::new;

        final ChannelHandler testInStackFrame = new ChannelDuplexHandler() {
            // marker boolean to signal that we're in the `channelRead` method
            private boolean inReadCall;
            private boolean writeUpgradeMessage;
            private boolean writeFlushed;
            private boolean first = true;

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (first) {
                    assertThat(inReadCall).isFalse();
                    assertThat(writeUpgradeMessage).isFalse();

                    inReadCall = true;
                    try {
                        super.channelRead(ctx, msg);
                        // All in the same call stack, the upgrade codec should receive the message,
                        // written the upgrade response, and upgraded the pipeline.
                        assertThat(writeUpgradeMessage).isTrue();
                        assertThat(writeFlushed).isFalse();
                        assertThat(ctx.pipeline().get(HttpServerCodec.class)).isNotNull();
                        assertThat(ctx.pipeline().get("marker")).isNotNull();
                    } finally {
                        inReadCall = false;
                        first = false;
                    }
                } else {
                    super.channelRead(ctx, msg);
                    assertThat(ctx.pipeline().get(HttpServerCodec.class)).isNull();
                    assertThat(ctx.pipeline().get(HttpServerUpgradeHandler.class)).isNull();
                    assertThat(ctx.pipeline().get("marker")).isNotNull();
                }
            }

            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
                // We ensure that we're in the read call and defer the write so we can
                // make sure the pipeline was reformed irrespective of the flush completing.
                assertThat(inReadCall).isTrue();
                writeUpgradeMessage = true;
                ctx.channel().eventLoop().execute(() -> ctx.write(msg, promise));
                promise.addListener((ChannelFutureListener) future -> writeFlushed = true);
            }
        };

        final HttpServerUpgradeHandler upgradeHandler = new HttpServerUpgradeHandler(httpServerCodec, factory);

        final EmbeddedChannel channel = new EmbeddedChannel(testInStackFrame, httpServerCodec, upgradeHandler);

        final String upgradeBody = "Hello";
        final String upgradeHeader =
                "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length: " + upgradeBody.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Connection: Upgrade, HTTP2-Settings\r\n" +
                "Upgrade: h2c\r\n" +
                "HTTP2-Settings: AAMAAABkAAQAAP__\r\n\r\n";
        final ByteBuf upgradeHeaderBuf = Unpooled.copiedBuffer(upgradeHeader, CharsetUtil.US_ASCII);

        assertThat(channel.writeInbound(upgradeHeaderBuf)).isFalse();
        assertThat(channel.pipeline().get(HttpServerCodec.class)).isNotNull();
        assertThat(channel.pipeline().get("marker")).isNotNull();

        final ByteBuf upgradeBodyBuf = Unpooled.copiedBuffer(upgradeBody, CharsetUtil.US_ASCII);
        assertThat(channel.writeInbound(upgradeBodyBuf)).isTrue();
        assertThat(channel.pipeline().get(HttpServerCodec.class)).isNull();

        channel.flushOutbound();
        final ByteBuf upgradeMessage = channel.readOutbound();
        final String expectedHttpResponse = "HTTP/1.1 101 Switching Protocols\r\n" +
                                            "connection: upgrade\r\n" +
                                            "upgrade: h2c\r\n\r\n";
        assertThat(upgradeMessage.toString(CharsetUtil.US_ASCII)).isEqualTo(expectedHttpResponse);
        assertThat(upgradeMessage.release()).isTrue();
        channel.finishAndReleaseAll();
    }

    @Test
    void upgradeFail() {
        final HttpServerCodec httpServerCodec = new HttpServerCodec();
        final UpgradeCodecFactory factory = TestUpgradeCodec::new;

        final HttpServerUpgradeHandler upgradeHandler = new HttpServerUpgradeHandler(httpServerCodec, factory);

        final EmbeddedChannel channel = new EmbeddedChannel(httpServerCodec, upgradeHandler);

        // Build a h2c upgrade request, but without connection header.
        final String upgradeString = "GET / HTTP/1.1\r\n" +
                                     "Host: example.com\r\n" +
                                     "Upgrade: h2c\r\n\r\n";
        final ByteBuf upgrade = Unpooled.copiedBuffer(upgradeString, CharsetUtil.US_ASCII);

        assertThat(channel.writeInbound(upgrade)).isTrue();
        assertThat(channel.pipeline().get(HttpServerCodec.class)).isNotNull();
        // Should not be removed.
        assertThat(channel.pipeline().get(HttpServerUpgradeHandler.class)).isNotNull();
        assertThat(channel.pipeline().get("marker")).isNull(); // Not added.

        final HttpRequest req = channel.readInbound();
        assertThat(req.protocolVersion()).isEqualTo(HttpVersion.HTTP_1_1);
        assertThat(req.headers().contains(HttpHeaderNames.UPGRADE, "h2c", false)).isTrue();
        assertThat(req.headers().contains(HttpHeaderNames.CONNECTION)).isFalse();
        ReferenceCountUtil.release(req);
        assertThat((Object) channel.readInbound()).isInstanceOf(LastHttpContent.class);

        // No response should be written because we're just passing through.
        channel.flushOutbound();
        assertThat((Object) channel.readOutbound()).isNull();
        channel.finishAndReleaseAll();
    }

    // Repeat to check if an illegal reference cnt exception is raised while using the response multiple times.
    @RepeatedTest(5)
    void upgradeFailWithInvalidSettingsHeader() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.httpPort())) {
            socket.setSoTimeout(1000);

            // Build a h2c upgrade request, but duplicated settings HTTP2-Settings.
            final String upgradeBody = "Hello";
            final PrintWriter writer = new PrintWriter(socket.getOutputStream());
            writer.print("POST / HTTP/1.1\r\n");
            writer.print("Content-Length: " + upgradeBody.getBytes(StandardCharsets.UTF_8).length + "\r\n");
            writer.print("Connection: Upgrade, HTTP2-Settings\r\n");
            writer.print("Upgrade: h2c\r\n");
            writer.print("HTTP2-Settings: AAMAAABkAAQAAP__\r\n");
            writer.print("HTTP2-Settings: AAMAAABkAAQAAP__\r\n\r\n");
            writer.flush();

            final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine()).isEqualTo("HTTP/1.1 400 Bad Request");

            // empty line
            in.readLine();

            assertThat(in.readLine()).isEqualTo("Invalid HTTP2-Settings header");
            assertThat(in.read()).isEqualTo(-1);
        }
    }
}

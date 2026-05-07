/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.NettyServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

class Http2FallbackChainTest {

    private static final List<String> connectionAttempts = new CopyOnWriteArrayList<>();

    // HTTP/1-only server that rejects both H2 preface and upgrade.
    @RegisterExtension
    static NettyServerExtension server = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new PrefaceDetector(),
                                  new HttpServerCodec(),
                                  new HttpObjectAggregator(65536),
                                  new SimpleHttp1Handler());
        }
    };

    @AfterEach
    void clearState() {
        connectionAttempts.clear();
        SessionProtocolNegotiationCache.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void fallbackChain(boolean useHttp2Preface) {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .useHttp2Preface(useHttp2Preface)
                                                  .build()) {
            final BlockingWebClient client =
                    WebClient.builder(server.endpoint().toUri(SessionProtocol.HTTP))
                             .factory(factory)
                             .build()
                             .blocking();
            final AggregatedHttpResponse response = client.get("/");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).contains("HTTP/1");
            if (useHttp2Preface) {
                assertThat(connectionAttempts).containsExactly("preface", "upgrade", "http1");
            } else {
                assertThat(connectionAttempts).containsExactly("upgrade", "preface", "http1");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void fallbackChainCachesPreferences(boolean useHttp2Preface) {
        // First connection: exercises the full fallback chain (3 attempts).
        try (ClientFactory factory1 = ClientFactory.builder()
                                                   .useHttp2Preface(useHttp2Preface)
                                                   .build()) {
            final BlockingWebClient client1 =
                    WebClient.builder(server.endpoint().toUri(SessionProtocol.HTTP))
                             .factory(factory1)
                             .build()
                             .blocking();
            final AggregatedHttpResponse response1 = client1.get("/");
            assertThat(response1.status()).isEqualTo(HttpStatus.OK);
            if (useHttp2Preface) {
                assertThat(connectionAttempts).containsExactly("preface", "upgrade", "http1");
            } else {
                assertThat(connectionAttempts).containsExactly("upgrade", "preface", "http1");
            }
        }

        // Clear attempt log but keep the static SessionProtocolNegotiationCache.
        connectionAttempts.clear();

        // Second connection with a new factory (new pool): should skip directly to HTTP/1.
        try (ClientFactory factory2 = ClientFactory.builder()
                                                   .useHttp2Preface(useHttp2Preface)
                                                   .build()) {
            final BlockingWebClient client2 =
                    WebClient.builder(server.endpoint().toUri(SessionProtocol.HTTP))
                             .factory(factory2)
                             .build()
                             .blocking();
            final AggregatedHttpResponse response2 = client2.get("/");
            assertThat(response2.status()).isEqualTo(HttpStatus.OK);
            assertThat(connectionAttempts).containsExactly("http1");
        }
    }

    // Detects H2 connection preface by inspecting raw bytes before the HTTP codec.
    private static final class PrefaceDetector extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf) {
                final ByteBuf buf = (ByteBuf) msg;
                if (buf.readableBytes() >= 3 &&
                    buf.getByte(buf.readerIndex()) == 'P' &&
                    buf.getByte(buf.readerIndex() + 1) == 'R' &&
                    buf.getByte(buf.readerIndex() + 2) == 'I') {
                    connectionAttempts.add("preface");
                    buf.release();
                    final ByteBuf content = Unpooled.copiedBuffer("Non http2 settings frame",
                                                                  CharsetUtil.UTF_8);
                    ctx.writeAndFlush(content);
                    return;
                }
            }
            ctx.fireChannelRead(msg);
        }
    }

    // Tracks upgrade vs plain HTTP/1 requests and responds with HTTP/1.1 200 OK.
    private static final class SimpleHttp1Handler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpRequest) {
                try {
                    final HttpRequest req = (HttpRequest) msg;
                    if (req.headers().contains(HttpHeaderNames.UPGRADE)) {
                        connectionAttempts.add("upgrade");
                    } else {
                        connectionAttempts.add("http1");
                    }

                    final ByteBuf content = Unpooled.copiedBuffer("Hello World - via HTTP/1",
                                                                  CharsetUtil.UTF_8);
                    final DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
                    ctx.writeAndFlush(response);
                } finally {
                    ReferenceCountUtil.release(msg);
                }
                return;
            }
            ctx.fireChannelRead(msg);
        }
    }
}

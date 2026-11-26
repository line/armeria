/*
 * Copyright 2026 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.TransportType;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2ConnectionPrefaceAndSettingsFrameWrittenEvent;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;

/**
 * Makes sure that the HTTP/2 connections are drained when the server is stopped without a graceful shutdown,
 * and that the active streams are closed at the end of the drain instead of being waited for.
 */
class GracefulShutdownDisabledHttp2DrainTest {

    private static final int STREAM_ID = 3;

    @Test
    void shouldDrainAndCloseActiveStreams() throws Exception {
        final CompletableFuture<Void> requestReceived = new CompletableFuture<>();
        final Server server =
                Server.builder()
                      .http(0)
                      .connectionDrainDuration(Duration.ofMillis(500))
                      .service("/slow", (ctx, req) -> {
                          requestReceived.complete(null);
                          return HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofSeconds(30));
                      })
                      .build();
        server.start().join();

        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> clientReady = new CompletableFuture<>();
        final Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(CommonPools.workerGroup());
        bootstrap.channel(TransportType.socketChannelType(CommonPools.workerGroup()));
        bootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addLast(new Http2ConnectionHandlerBuilder()
                                              .server(false)
                                              .frameListener(new Http2FrameAdapter() {
                                                  @Override
                                                  public void onGoAwayRead(ChannelHandlerContext ctx,
                                                                           int lastStreamId, long errorCode,
                                                                           ByteBuf debugData) {
                                                      frames.add("GOAWAY " + lastStreamId);
                                                  }

                                                  @Override
                                                  public void onHeadersRead(ChannelHandlerContext ctx,
                                                                            int streamId, Http2Headers headers,
                                                                            int padding, boolean endOfStream) {
                                                      frames.add("HEADERS " + streamId);
                                                  }
                                              })
                                              .gracefulShutdownTimeoutMillis(0)
                                              .build());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        if (evt instanceof Http2ConnectionPrefaceAndSettingsFrameWrittenEvent) {
                            clientReady.complete(null);
                        }
                        ctx.fireUserEventTriggered(evt);
                    }
                });
            }
        });

        final Channel channel = bootstrap.connect(new InetSocketAddress("127.0.0.1", server.activeLocalPort()))
                                         .syncUninterruptibly().channel();
        try {
            clientReady.join();
            final Http2ConnectionHandler http2Client = channel.pipeline().get(Http2ConnectionHandler.class);
            channel.eventLoop().execute(() -> {
                final ChannelHandlerContext ctx = channel.pipeline().context(http2Client);
                final Http2Headers headers = new DefaultHttp2Headers().method("GET").scheme("http")
                                                                      .path("/slow");
                http2Client.encoder().writeHeaders(ctx, STREAM_ID, headers, 0, true, ctx.newPromise());
                http2Client.flush(ctx);
            });
            requestReceived.join();

            server.stop().join();

            // The first GOAWAY lets the client know that the connection is being shut down.
            assertThat(frames.poll(10, TimeUnit.SECONDS)).isEqualTo("GOAWAY " + Integer.MAX_VALUE);
            // The final GOAWAY is sent after the drain, and the connection is closed without waiting for
            // the active stream.
            assertThat(frames.poll(10, TimeUnit.SECONDS)).isEqualTo("GOAWAY " + STREAM_ID);
            assertThat(channel.closeFuture().await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(frames).isEmpty();
        } finally {
            channel.close().syncUninterruptibly();
            server.stop().join();
        }
    }
}

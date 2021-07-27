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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2ConnectionPrefaceAndSettingsFrameWrittenEvent;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AsciiString;

public class InitiateConnectionShutdownTest {

    private static final int STREAM_ID = 3;
    private static final int PADDING = 0;

    @Mock
    private Http2FrameListener clientListener;

    private Http2ConnectionHandler http2Client;
    private Channel clientChannel;

    private static Http2Headers getHttp2Headers(String path) {
        return new DefaultHttp2Headers(false)
                .method(new AsciiString("GET"))
                .scheme(new AsciiString("http"))
                .path(new AsciiString(path));
    }

    @RegisterExtension
    static final ServerExtension goAwayServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Get("/goaway_async")
                public CompletableFuture<HttpResponse> goAway(
                        ServiceRequestContext ctx, @Param("duration") Optional<Long> durationMillis) {
                    if (durationMillis.isPresent()) {
                        ctx.initiateConnectionShutdown(Duration.ofMillis(durationMillis.get()));
                    } else {
                        ctx.initiateConnectionShutdown();
                    }
                    final CompletableFuture<HttpResponse> cf = new CompletableFuture<>();
                    // Respond with some delay, GOAWAY frame should not be blocked and shoudl be sent before
                    // the response.
                    ctx.eventLoop()
                       .schedule(() -> HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Go away!"),
                                 100, TimeUnit.MILLISECONDS)
                       .addListener(f -> {
                           if (f.cause() != null) {
                               cf.completeExceptionally(f.cause());
                           } else {
                               cf.complete((HttpResponse) f.getNow());
                           }
                       });
                    return cf;
                }

                @Blocking
                @Get("/goaway_blocking")
                public HttpResponse goAwayBlocking(ServiceRequestContext ctx,
                                                   @Param("duration") Optional<Long> durationMillis)
                        throws InterruptedException {
                    if (durationMillis.isPresent()) {
                        ctx.initiateConnectionShutdown(Duration.ofMillis(durationMillis.get()));
                    } else {
                        ctx.initiateConnectionShutdown();
                    }
                    // Respond with some delay, GOAWAY frame should not be blocked and should be sent before
                    // the response.
                    Thread.sleep(100);
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Go away!");
                }
            });
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        final AtomicBoolean clientSetupFinished = new AtomicBoolean();
        final Bootstrap clientBootstrap = new Bootstrap();
        clientBootstrap.group(CommonPools.workerGroup());
        clientBootstrap.channel(TransportType.socketChannelType(CommonPools.workerGroup()));
        clientBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                final ChannelPipeline p = ch.pipeline();
                p.addLast(new LoggingHandler());
                p.addLast(new Http2ConnectionHandlerBuilder()
                                  .server(false)
                                  .frameListener(clientListener)
                                  .validateHeaders(false)
                                  .gracefulShutdownTimeoutMillis(0)
                                  .build());
                p.addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                        if (evt instanceof Http2ConnectionPrefaceAndSettingsFrameWrittenEvent) {
                            clientSetupFinished.set(true);
                            ctx.pipeline().remove(this);
                        }
                    }
                });
            }
        });

        final ChannelFuture channelFuture = clientBootstrap.connect(
                goAwayServer.socketAddress(SessionProtocol.H2C));
        assertTrue(channelFuture.awaitUninterruptibly().isSuccess());
        clientChannel = channelFuture.channel();
        await().timeout(Duration.ofSeconds(5)).untilTrue(clientSetupFinished);
        http2Client = clientChannel.pipeline().get(Http2ConnectionHandler.class);
    }

    @ParameterizedTest
    @CsvSource({
            "/goaway_async",
            "/goaway_async?duration=-1",
            "/goaway_async?duration=0",
            "/goaway_blocking",
            "/goaway_blocking?duration=-1",
            "/goaway_blocking?duration=0",
    })
    void initiateConnectionShutdownWihtoutGracePeriodHttp2(String path) throws Exception {
        final AtomicBoolean finished = new AtomicBoolean();
        clientChannel.eventLoop().execute(() -> {
            final ChannelHandlerContext ctx = clientChannel.pipeline().firstContext();
            final Http2Headers headers = getHttp2Headers(path);
            http2Client.encoder().writeHeaders(ctx, STREAM_ID, headers, PADDING, false, ctx.newPromise());
            http2Client.flush(ctx);
        });
        when(clientListener.onDataRead(any(), anyInt(), any(), anyInt(), anyBoolean())).thenAnswer(
                invocation -> {
                    finished.set(true);
                    return 0;
                });
        await().timeout(Duration.ofSeconds(2)).untilTrue(finished);
        final InOrder inOrder = inOrder(clientListener);
        inOrder.verify(clientListener, never()).onGoAwayRead(any(ChannelHandlerContext.class),
                                                             eq(Integer.MAX_VALUE),
                                                             eq(Http2Error.NO_ERROR.code()),
                                                             any(ByteBuf.class));
        inOrder.verify(clientListener).onGoAwayRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                    eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class));
        inOrder.verify(clientListener).onDataRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                  any(ByteBuf.class), anyInt(), eq(true));
    }

    @ParameterizedTest
    @CsvSource({
            "/goaway_async?duration=1",
            "/goaway_blocking?duration=1",
    })
    void initiateConnectionShutdownDelayedHttp2(String path) throws Exception {
        final AtomicBoolean finished = new AtomicBoolean();
        clientChannel.eventLoop().execute(() -> {
            final ChannelHandlerContext ctx = clientChannel.pipeline().firstContext();
            final Http2Headers headers = getHttp2Headers(path);
            http2Client.encoder().writeHeaders(ctx, STREAM_ID, headers, PADDING, false, ctx.newPromise());
            http2Client.flush(ctx);
        });
        when(clientListener.onDataRead(any(), anyInt(), any(), anyInt(), anyBoolean())).thenAnswer(
                invocation -> {
                    finished.set(true);
                    return 0;
                });
        await().timeout(Duration.ofSeconds(2)).untilTrue(finished);
        final InOrder inOrder = inOrder(clientListener);
        inOrder.verify(clientListener).onGoAwayRead(any(ChannelHandlerContext.class), eq(Integer.MAX_VALUE),
                                                    eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class));
        inOrder.verify(clientListener).onGoAwayRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                    eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class));
        inOrder.verify(clientListener).onDataRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                  any(ByteBuf.class), anyInt(), eq(true));
    }

    @ParameterizedTest
    @CsvSource({
            "/goaway_async?duration=200",
            "/goaway_blocking?duration=200",
    })
    void initiateConnectionShutdownOnlyGracePeriodHttp2(String path) throws Exception {
        final AtomicBoolean finished = new AtomicBoolean();
        clientChannel.eventLoop().execute(() -> {
            final ChannelHandlerContext ctx = clientChannel.pipeline().firstContext();
            final Http2Headers headers = getHttp2Headers(path);
            http2Client.encoder().writeHeaders(ctx, STREAM_ID, headers, PADDING, false, ctx.newPromise());
            http2Client.flush(ctx);
        });
        when(clientListener.onDataRead(any(), anyInt(), any(), anyInt(), anyBoolean())).thenAnswer(
                invocation -> {
                    finished.set(true);
                    return 0;
                });
        await().timeout(Duration.ofSeconds(2)).untilTrue(finished);
        final InOrder inOrder = inOrder(clientListener);
        inOrder.verify(clientListener).onGoAwayRead(any(ChannelHandlerContext.class), eq(Integer.MAX_VALUE),
                                                    eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class));
        inOrder.verify(clientListener).onDataRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                  any(ByteBuf.class), anyInt(), eq(true));
    }

    @ParameterizedTest
    @CsvSource({
            "/goaway_async",
            "/goaway_async?duration=-1",
            "/goaway_async?duration=0",
            "/goaway_async?duration=1",
            "/goaway_async?duration=100",
            "/goaway_blocking",
            "/goaway_blocking?duration=-1",
            "/goaway_blocking?duration=0",
            "/goaway_async?duration=1",
            "/goaway_async?duration=100",
    })
    void initiateConnectionShutdownHttp1(String path) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(goAwayServer.httpUri() + path);
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.containsHeader("Connection")).isTrue();
                assertThat(res.getHeaders("Connection"))
                        .extracting(Header::getValue).containsExactly("close");
            }
        }
    }
}

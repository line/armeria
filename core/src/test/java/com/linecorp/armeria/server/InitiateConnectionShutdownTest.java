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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;

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
import io.netty.buffer.Unpooled;
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

class InitiateConnectionShutdownTest {

    @RegisterExtension
    static final ServerExtension goAwayServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new AnnotatedTestService());
            sb.connectionDrainDurationMicros(0);
        }
    };
    @RegisterExtension
    static final ServerExtension goAwayServerNoopKeepAliveHandler = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new AnnotatedTestService());
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);
            sb.maxConnectionAgeMillis(0);
            sb.maxNumRequestsPerConnection(0);
            sb.connectionDrainDurationMicros(0);
        }
    };
    private static final int STREAM_ID = 3;
    private static final int PADDING = 0;
    private static final ByteBuf DEBUG_DATA = Unpooled.unreleasableBuffer(
            Unpooled.copiedBuffer("app-requested", StandardCharsets.UTF_8));
    private static final AtomicBoolean connectionClosed = new AtomicBoolean();
    @Mock
    private Http2FrameListener clientListener;
    private Http2ConnectionHandler http2Client;
    private Channel clientChannel;
    private Cleaner cleaner;

    private static Http2Headers getHttp2Headers(String path) {
        return new DefaultHttp2Headers(false)
                .method(new AsciiString("GET"))
                .scheme(new AsciiString("http"))
                .path(new AsciiString(path));
    }

    private void makeHttp2Request(String path) throws Exception {
        final AtomicBoolean finished = new AtomicBoolean();
        doAnswer((Answer<Integer>) invocation -> {
            finished.set(true);
            return 0;
        }).when(clientListener).onDataRead(any(), anyInt(), any(), anyInt(), anyBoolean());
        doAnswer((Answer<Void>) invocation -> {
            // Retain buffer for comparison in tests.
            final ByteBuf buf = invocation.getArgument(3);
            buf.retain();
            // Cleanup after test finish.
            cleaner.add(buf::release);
            return null;
        }).when(clientListener).onGoAwayRead(any(ChannelHandlerContext.class), anyInt(), anyLong(),
                                             any(ByteBuf.class));
        clientChannel.eventLoop().execute(() -> {
            final ChannelHandlerContext ctx = clientChannel.pipeline().firstContext();
            final Http2Headers headers = getHttp2Headers(path);
            http2Client.encoder().writeHeaders(ctx, STREAM_ID, headers, PADDING, true, ctx.newPromise());
            http2Client.flush(ctx);
        });
        await().untilTrue(finished);
        await().untilTrue(connectionClosed);
    }

    @BeforeEach
    void setUp() throws Exception {
        cleaner = new Cleaner();
        connectionClosed.set(false);
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
                            ctx.pipeline().remove(ctx.handler());
                            clientSetupFinished.set(true);
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

    @AfterEach
    void tearDown() throws Exception {
        cleaner.close();
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
    void initiateConnectionShutdownWithoutDrainHttp2(String path) throws Exception {
        makeHttp2Request(path);
        final InOrder inOrder = inOrder(clientListener);
        inOrder.verify(clientListener, never()).onGoAwayRead(any(ChannelHandlerContext.class),
                                                             eq(Integer.MAX_VALUE),
                                                             eq(Http2Error.NO_ERROR.code()),
                                                             any(ByteBuf.class));
        inOrder.verify(clientListener).onGoAwayRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                    eq(Http2Error.NO_ERROR.code()), eq(DEBUG_DATA));
        inOrder.verify(clientListener).onDataRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                  any(ByteBuf.class), anyInt(), eq(true));
    }

    @ParameterizedTest
    @CsvSource({
            "/goaway_async?duration=1",
            "/goaway_blocking?duration=1",
    })
    void initiateConnectionShutdownWithDrainHttp2(String path) throws Exception {
        makeHttp2Request(path);
        final InOrder inOrder = inOrder(clientListener);
        inOrder.verify(clientListener).onGoAwayRead(any(ChannelHandlerContext.class), eq(Integer.MAX_VALUE),
                                                    eq(Http2Error.NO_ERROR.code()), eq(DEBUG_DATA));
        inOrder.verify(clientListener).onGoAwayRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                    eq(Http2Error.NO_ERROR.code()), eq(DEBUG_DATA));
        inOrder.verify(clientListener).onDataRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                  any(ByteBuf.class), anyInt(), eq(true));
    }

    @ParameterizedTest
    @CsvSource({
            "/goaway_async?duration=500",
            "/goaway_blocking?duration=500",
    })
    void initiateConnectionShutdownCloseBeforeDrainEndHttp2(String path) throws Exception {
        makeHttp2Request(path);
        final InOrder inOrder = inOrder(clientListener);
        inOrder.verify(clientListener).onGoAwayRead(any(ChannelHandlerContext.class), eq(Integer.MAX_VALUE),
                                                    eq(Http2Error.NO_ERROR.code()), eq(DEBUG_DATA));
        inOrder.verify(clientListener).onDataRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                  any(ByteBuf.class), anyInt(), eq(true));
        inOrder.verify(clientListener).onGoAwayRead(any(ChannelHandlerContext.class), eq(STREAM_ID),
                                                    eq(Http2Error.NO_ERROR.code()), eq(DEBUG_DATA));
    }

    @ParameterizedTest
    @CsvSource({
            "/goaway_async",
            "/goaway_async?duration=-1",
            "/goaway_async?duration=0",
            "/goaway_async?duration=1",
            "/goaway_async?duration=500",
            "/goaway_blocking",
            "/goaway_blocking?duration=-1",
            "/goaway_blocking?duration=0",
            "/goaway_async?duration=1",
            "/goaway_async?duration=500",
    })
    void initiateConnectionShutdownHttp1(String path) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(goAwayServer.httpUri() + path);
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getCode()).isEqualTo(200);
                assertThat(res.containsHeader("Connection")).isTrue();
                assertThat(res.getHeaders("Connection"))
                        .extracting(Header::getValue).containsExactly("close");
            }
        }
        await().untilTrue(connectionClosed);
    }

    @ParameterizedTest
    @CsvSource({
            "/goaway_async",
            "/goaway_async?duration=-1",
            "/goaway_async?duration=0",
            "/goaway_async?duration=1",
            "/goaway_async?duration=500",
            "/goaway_blocking",
            "/goaway_blocking?duration=-1",
            "/goaway_blocking?duration=0",
            "/goaway_async?duration=1",
            "/goaway_async?duration=500",
    })
    void initiateConnectionShutdownHttp1NoopKeepAliveHandler(String path) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(goAwayServerNoopKeepAliveHandler.httpUri() + path);
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getCode()).isEqualTo(200);
                assertThat(res.containsHeader("Connection")).isTrue();
                assertThat(res.getHeaders("Connection"))
                        .extracting(Header::getValue).containsExactly("close");
            }
        }
        await().untilTrue(connectionClosed);
    }

    private static class AnnotatedTestService {
        @Get("/goaway_async")
        public HttpResponse goAway(
                ServiceRequestContext ctx, @Param("duration") Optional<Long> durationMillis) {
            final CompletableFuture<Void> future;
            if (durationMillis.isPresent()) {
                future = ctx.initiateConnectionShutdown(Duration.ofMillis(durationMillis.get()));
            } else {
                future = ctx.initiateConnectionShutdown();
            }
            future.thenAccept(f -> connectionClosed.set(true));
            // Respond with some delay, GOAWAY frame should not be blocked and should be sent before
            // the response.
            return HttpResponse.delayed(
                    HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Go away!"),
                    Duration.ofMillis(200));
        }

        @Blocking
        @Get("/goaway_blocking")
        public HttpResponse goAwayBlocking(ServiceRequestContext ctx,
                                           @Param("duration") Optional<Long> durationMillis)
                throws InterruptedException {
            final CompletableFuture<Void> future;
            if (durationMillis.isPresent()) {
                future = ctx.initiateConnectionShutdown(Duration.ofMillis(durationMillis.get()));
            } else {
                future = ctx.initiateConnectionShutdown();
            }
            future.thenAccept(f -> connectionClosed.set(true));
            // Respond with some delay, GOAWAY frame should not be blocked and should be sent before
            // the response.
            Thread.sleep(200);
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Go away!");
        }
    }

    private static class Cleaner implements AutoCloseable {
        private final LinkedBlockingDeque<Runnable> runnables = new LinkedBlockingDeque<>();

        void add(Runnable runnable) {
            runnables.push(runnable);
        }

        @Override
        public void close() throws Exception {
            while (!runnables.isEmpty()) {
                runnables.pop().run();
            }
        }
    }
}

/*
 * Copyright 2025 LINE Corporation
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
 *
 */

package com.linecorp.armeria.client;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.testing.NettyServerExtension;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;

class Http1ClientClosedSessionExceptionTest {

    private static final Logger logger = LoggerFactory.getLogger(Http1ClientClosedSessionExceptionTest.class);

    @RegisterExtension
    static NettyServerExtension http1Server = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpObjectAggregator(1024));
            ch.pipeline().addLast(new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (msg instanceof FullHttpRequest) {
                        final HttpResponse response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                        // Make sure the connection is closed after the response is sent.
                        response.headers().add(HttpHeaderNames.CONNECTION, "close");
                        ctx.writeAndFlush(response)
                           .addListener(ChannelFutureListener.CLOSE);
                    } else {
                        ctx.fireChannelRead(msg);
                    }
                }
            });
        }
    };

    @Test
    void shouldSendHttp1RequestConcurrently() throws Throwable {
        final WebClient client = WebClient.of(SessionProtocol.HTTP, http1Server.endpoint());

        SessionProtocolNegotiationCache.setUnsupported(
                new InetSocketAddress("127.0.0.1", http1Server.address().getPort()),
                SessionProtocol.H2C);
        final AtomicReference<Throwable> failed = new AtomicReference<>();
        final Runnable loader = () -> {
            while (failed.get() == null) {
                try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                    try {
                        final CompletableFuture<AggregatedHttpResponse> future = client.get("/").aggregate();
                        final ClientRequestContext ctx = captor.get();
                        future.join();
                        final RequestLog log = ctx.log().whenComplete().join();
                        final Throwable responseCause = log.responseCause();
                        if (responseCause != null) {
                            failed.set(responseCause);
                        }
                    } catch (Throwable ex) {
                        failed.set(new RuntimeException("Failed to receive the response. ctx: " + captor.get(),
                                                        ex));
                    }
                }
            }
        };

        for (int i = 0; i < 5; i++) {
            CommonPools.blockingTaskExecutor().execute(loader);
        }

        for (int i = 0; i < 5; i++) {
            Thread.sleep(1000);
            if (failed.get() != null) {
                throw failed.get();
            }
        }
        logger.info("All requests completed successfully");
    }
}

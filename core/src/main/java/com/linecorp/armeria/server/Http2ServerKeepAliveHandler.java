/*
 * Copyright 2020 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.internal.common.Http2KeepAliveHandler;

import io.micrometer.core.instrument.Timer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;

final class Http2ServerKeepAliveHandler extends Http2KeepAliveHandler {

    private static final ByteBuf MAX_CONNECTION_AGE_DEBUG = Unpooled.wrappedBuffer("max-age".getBytes());
    private boolean isFinalGoAwaySent;
    private final Http2ConnectionEncoder encoder;

    Http2ServerKeepAliveHandler(Channel channel, Http2ConnectionEncoder encoder, Timer keepAliveTimer,
                                long idleTimeoutMillis, long pingIntervalMillis,
                                long maxConnectionAgeMillis, int maxNumRequestsPerConnection) {
        super(channel, encoder.frameWriter(), "server", keepAliveTimer,
              idleTimeoutMillis, pingIntervalMillis, maxConnectionAgeMillis, maxNumRequestsPerConnection);
        this.encoder = requireNonNull(encoder, "encoder");

    }

    @Override
    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
        final HttpServer server = HttpServer.get(ctx);
        return server != null && server.unfinishedRequests() != 0;
    }

    @Override
    public CompletableFuture<Void> initiateConnectionShutdown(ChannelHandlerContext ctx, Duration gracePeriod) {
        final CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        ctx.channel().closeFuture().addListener(f -> {
            if (f.cause() == null) {
                completableFuture.complete(null);
            } else {
                completableFuture.completeExceptionally(f.cause());
            }
        });
        if (gracePeriod.compareTo(Duration.ZERO) > 0) {
            // Positive grace period, send initial GOAWAY frame and schedule shutdown in the future.
            sendGoAway(ctx, Integer.MAX_VALUE);
            ctx.channel().eventLoop().schedule(() -> sendFinalGoAway(ctx),
                                               gracePeriod.toNanos(), TimeUnit.NANOSECONDS);
        } else {
            // Close connection immediately.
            sendFinalGoAway(ctx);
        }
        return completableFuture;
    }

    private void sendGoAway(ChannelHandlerContext ctx, int lastStreamId) {
        encoder.writeGoAway(ctx, lastStreamId, Http2Error.NO_ERROR.code(), MAX_CONNECTION_AGE_DEBUG.retain(),
                            ctx.newPromise());
        ctx.flush();
    }

    private void sendFinalGoAway(ChannelHandlerContext ctx) {
        if (isFinalGoAwaySent) {
            return;
        }
        final int lastStreamId = encoder.connection().remote().lastStreamCreated();
        sendGoAway(ctx, lastStreamId);
        isFinalGoAwaySent = true;
        destroy();
    }

}

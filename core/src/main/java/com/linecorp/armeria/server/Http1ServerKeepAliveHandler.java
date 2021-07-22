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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.internal.common.AbstractKeepAliveHandler;
import com.linecorp.armeria.internal.common.Http1KeepAliveHandler;

import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;

final class Http1ServerKeepAliveHandler extends Http1KeepAliveHandler {

    Http1ServerKeepAliveHandler(Channel channel, Timer keepAliveTimer,
                                long idleTimeoutMillis, long maxConnectionAgeMillis,
                                int maxNumRequestsPerConnection) {
        super(channel, "server", keepAliveTimer, idleTimeoutMillis, /* pingIntervalMillis(unsupported) */ 0,
              maxConnectionAgeMillis, maxNumRequestsPerConnection);
    }

    @Override
    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean pingResetsPreviousPing() {
        return false;
    }

    @Override
    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
        final HttpServer server = HttpServer.get(ctx);
        return server != null && server.unfinishedRequests() != 0;
    }

    /**
     * Calls {@link AbstractKeepAliveHandler#destroy()} which results in "Connection: close" to be sent
     * to the client. If provided grace period is greater than zero - schedules
     * {@link AbstractKeepAliveHandler#destroy()} to happen after the grace period.
     */
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
            ctx.channel().eventLoop().schedule(this::destroy, gracePeriod.toNanos(), TimeUnit.NANOSECONDS);
        } else {
            destroy();
        }
        return completableFuture;
    }

}

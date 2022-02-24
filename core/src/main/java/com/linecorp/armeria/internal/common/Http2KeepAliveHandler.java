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

package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2PingFrame;

/**
 * A {@link KeepAliveHandler} that sends an HTTP/2 PING frame
 * when neither read nor write was performed within the specified {@code pingIntervalMillis},
 * and closes the connection when neither read nor write was performed within
 * the given {@code idleTimeoutMillis}.
 *
 * <p>Once a {@link Http2PingFrame} is written, then either an ACK for the {@link Http2PingFrame} or any data
 * is read on connection will invalidate the condition that triggers connection closure.
 *
 * <p>This class is <b>not</b> thread-safe and all methods are to be called from single thread such
 * as {@link EventLoop}.
 *
 * @see Flags#defaultClientIdleTimeoutMillis()
 * @see Flags#defaultServerIdleTimeoutMillis()
 * @see Flags#defaultPingIntervalMillis()
 */
public abstract class Http2KeepAliveHandler extends AbstractKeepAliveHandler {

    private static final Logger logger = LoggerFactory.getLogger(Http2KeepAliveHandler.class);

    @Nullable
    private final Stopwatch stopwatch = logger.isDebugEnabled() ? Stopwatch.createUnstarted() : null;
    private final Http2FrameWriter frameWriter;
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    private final Channel channel;

    private long lastPingPayload;

    protected Http2KeepAliveHandler(Channel channel, Http2FrameWriter frameWriter, String name,
                                    Timer keepAliveTimer, long idleTimeoutMillis, long pingIntervalMillis,
                                    long maxConnectionAgeMillis, int maxNumRequestsPerConnection) {
        super(channel, name, keepAliveTimer, idleTimeoutMillis, pingIntervalMillis,
              maxConnectionAgeMillis, maxNumRequestsPerConnection);
        this.channel = requireNonNull(channel, "channel");
        this.frameWriter = requireNonNull(frameWriter, "frameWriter");
    }

    @Override
    public boolean isHttp2() {
        return true;
    }

    @Override
    protected final ChannelFuture writePing(ChannelHandlerContext ctx) {
        lastPingPayload = random.nextLong();
        final ChannelFuture future = frameWriter.writePing(ctx, false, lastPingPayload, ctx.newPromise());
        ctx.flush();
        return future;
    }

    @Override
    public final void onPingAck(long data) {
        final long elapsed = getStopwatchElapsedInNanos();
        if (!isGoodPingAck(data)) {
            return;
        }

        onPing();
        final Future<?> shutdownFuture = shutdownFuture();
        if (shutdownFuture != null) {
            final boolean isCancelled = shutdownFuture.cancel(false);
            if (!isCancelled) {
                logger.debug("{} shutdownFuture cannot be cancelled because of late PING ACK", channel);
            }
        }
        logger.debug("{} PING(ACK=1, DATA={}) received in {} ns", channel, lastPingPayload, elapsed);
    }

    @Override
    protected final boolean pingResetsPreviousPing() {
        return true;
    }

    private boolean isGoodPingAck(long data) {
        // 'isPendingPingAck()' can return false when channel read some data other than PING ACK frame
        // or a PING ACK is received without sending PING in first place.
        if (!isPendingPingAck()) {
            logger.debug("{} PING(ACK=1, DATA={}) ignored", channel, data);
            return false;
        }
        if (lastPingPayload != data) {
            logger.debug("{} Unexpected PING(ACK=1, DATA={}) received, " +
                         "but expecting PING(ACK=1, DATA={})", channel, data, lastPingPayload);
            return false;
        }
        return true;
    }

    @VisibleForTesting
    final long lastPingPayload() {
        return lastPingPayload;
    }

    private long getStopwatchElapsedInNanos() {
        if (stopwatch == null) {
            return -1;
        }
        return stopwatch.elapsed(TimeUnit.NANOSECONDS);
    }
}

/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * Synchronizes the lifecycle of an HTTP2 stream with its corresponding user-facing
 * HTTP constructs. All methods in this class are expected to be invoked from
 * the {@link Channel}'s {@link EventExecutor}.
 */
final class Http2StreamLifecycleHandler implements SafeCloseable {

    private final Http2ConnectionEncoder encoder;
    private final ChannelHandlerContext ctx;

    private final Map<Integer, ScheduledFuture<?>> streamResetFutures = new IntObjectHashMap<>();

    Http2StreamLifecycleHandler(ChannelHandlerContext ctx,
                                AbstractHttp2ConnectionHandler handler) {
        encoder = handler.encoder();
        this.ctx = ctx;
    }

    /**
     * Invoked when a {@link Http2Stream}'s corresponding {@link HttpRequest} and {@link HttpResponse}
     * are closed.
     */
    void maybeResetStream(int streamId, Http2Error http2Error, long delayMillis) {
        if (!canResetStream(streamId)) {
            return;
        }
        if (delayMillis == 0) {
            maybeResetStream0(streamId, http2Error);
        } else if (delayMillis > 0) {
            final ScheduledFuture<?> scheduled = ctx.executor().schedule(() -> {
                maybeResetStream0(streamId, http2Error);
            }, delayMillis, TimeUnit.MILLISECONDS);
            streamResetFutures.put(streamId, scheduled);
        }
    }

    private void maybeResetStream0(int streamId, Http2Error http2Error) {
        if (!canResetStream(streamId)) {
            return;
        }
        encoder.writeRstStream(ctx, streamId, http2Error.code(), ctx.voidPromise());
        ctx.flush();
    }

    private boolean canResetStream(int streamId) {
        if (!ctx.channel().isActive()) {
            return false;
        }
        final Http2Stream stream = encoder.connection().stream(streamId);
        if (stream == null) {
            return false;
        }
        return stream.state().remoteSideOpen();
    }

    /**
     * Invoked every time a stream is closed, which allows clean up of pre-scheduled
     * stream close futures.
     */
    void notifyStreamClosed(int streamId) {
        final ScheduledFuture<?> future = streamResetFutures.remove(streamId);
        if (future != null) {
            future.cancel(true);
        }
    }

    @Override
    public void close() {
        if (!ctx.executor().inEventLoop()) {
            ctx.executor().execute(this::close);
            return;
        }
        for (ScheduledFuture<?> future : streamResetFutures.values()) {
            future.cancel(true);
        }
        streamResetFutures.clear();
    }
}

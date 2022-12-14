/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.internal.common.websocket;

import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.closeWebSocketFrameFrom;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;

public final class WebSocketCloseHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketCloseHandler.class);

    private final ServiceRequestContext ctx;
    private final HttpResponseWriter writer;
    private final WebSocketFrameEncoder encoder;
    private final long closeTimeoutMillis;

    private boolean inboundStreamClosed;
    private volatile boolean isCloseFrameSent;
    private boolean isCloseFrameReceived;

    @Nullable
    private ScheduledFuture<?> closeInboundStreamFuture;
    private boolean closed;

    public WebSocketCloseHandler(ServiceRequestContext ctx, HttpResponseWriter writer,
                                 WebSocketFrameEncoder encoder, long closeTimeoutMillis) {
        this.ctx = ctx;
        this.writer = writer;
        this.encoder = encoder;
        this.closeTimeoutMillis = closeTimeoutMillis;
    }

    /**
     * Closes the outbound stream if {@code isCloseFrameReceived} is true. If not, close or schedule closing
     * outbound stream depending on {@code closeTimeoutMillis}.
     */
    public void closeFrameSent() {
        // Should be called by the eventLoop.
        assert ctx.eventLoop().inEventLoop();
        if (isCloseFrameSent || closed) {
            return;
        }
        isCloseFrameSent = true;
        // Immediately close both streams if close frames are sent and received.
        closeStreams0(null, isCloseFrameReceived);
    }

    /**
     * Close the inbound stream and also close the outbound stream if the close frame is sent
     * but writer.close() isn't called yet.
     */
    void receivedCloseFrame() {
        if (ctx.eventLoop().inEventLoop()) {
            receivedCloseFrame0();
        } else {
            ctx.eventLoop().execute(this::receivedCloseFrame0);
        }
    }

    void receivedCloseFrame0() {
        if (isCloseFrameReceived || closed) {
            return;
        }
        isCloseFrameReceived = true;
        if (isCloseFrameSent) {
            // Immediately close both streams if close frames are sent and received.
            closeStreams0(null, true);
        } else {
            // Just close the inbound stream only.
            closeInboundStream(null);
        }
    }

    private void closeInboundStream(@Nullable Throwable cause) {
        if (inboundStreamClosed) {
            return;
        }
        final Channel channel = ctx.log()
                                   .ensureAvailable(RequestLogProperty.SESSION)
                                   .channel();
        assert channel != null;
        if (cause != null) {
            WebSocketUtil.closeWebSocketInboundStream(channel, cause);
        } else {
            WebSocketUtil.closeWebSocketInboundStream(channel);
        }
        inboundStreamClosed = true;
    }

    /**
     * Called from {@code WebSocketResponseSubscriber}.
     */
    public boolean isCloseFrameSent() {
        assert ctx.eventLoop().inEventLoop();
        return isCloseFrameSent;
    }

    /**
     * Closes both inbound and outbound streams.
     */
    public void closeStreams(Throwable cause, boolean immediate) {
        if (ctx.eventLoop().inEventLoop()) {
            closeStreams0(cause, immediate);
        } else {
            ctx.eventLoop().execute(() -> closeStreams0(cause, immediate));
        }
    }

    private void closeStreams0(@Nullable Throwable cause, boolean immediate) {
        if (closed) {
            return;
        }
        if (cause != null) {
            if (cause instanceof ClosedStreamException && (isCloseFrameSent || isCloseFrameReceived)) {
                // After a close frame is sent or received, a Closed(Stream|Session)Exception can be raised
                // if the connection is closed by the peer. If so, we just close the both streams.
                closeStreams1(cause);
                return;
            }
            // Set the cause.
            ctx.logBuilder().responseCause(cause);

            // Provide an exception handler.
            final CloseWebSocketFrame closeWebSocketFrame = closeWebSocketFrameFrom(cause);
            if (writer.tryWrite(HttpData.wrap(encoder.encode(ctx, closeWebSocketFrame)))) {
                logger.trace("Close frame is sent: {}", closeWebSocketFrame);
                isCloseFrameSent = true;
            }
        }
        if (immediate || closeTimeoutMillis == 0) {
            closeStreams1(cause);
            if (closeInboundStreamFuture != null) {
                closeInboundStreamFuture.cancel(true);
            }
        } else {
            if (closeInboundStreamFuture != null) {
                // Simply return because there's already a scheduled job.
                return;
            }
            closeInboundStreamFuture = ctx.eventLoop().schedule(() -> closeStreams1(cause),
                                                                closeTimeoutMillis,
                                                                TimeUnit.MILLISECONDS);
        }
    }

    private void closeStreams1(@Nullable Throwable cause) {
        // Do not use cause to close the writer because we already sent the close frame using the cause.
        writer.close();
        closeInboundStream(cause);
        closed = true;
    }
}

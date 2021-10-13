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
package com.linecorp.armeria.internal.common.websocket;

import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;

public final class WebSocketCloseHandler {

    private final ServiceRequestContext ctx;
    private final HttpResponseWriter writer;
    private final long closeTimeoutMillis;

    boolean closeFrameReceived;
    boolean closeFrameSent;
    private boolean streamsClosed;

    @Nullable
    private ScheduledFuture<?> scheduledFuture;

    public WebSocketCloseHandler(ServiceRequestContext ctx, HttpResponseWriter writer,
                                 long closeTimeoutMillis) {
        this.ctx = ctx;
        this.writer = writer;
        this.closeTimeoutMillis = closeTimeoutMillis;
    }

    public void closeFrameReceived() {
        closeFrameReceived = true;
        if (closeFrameSent) {
            closeStreams();
        }
    }

    public void closeFrameSent() {
        closeFrameSent = true;
        if (closeFrameReceived || closeTimeoutMillis == 0) {
            closeStreams();
        } else {
            scheduledFuture = ctx.eventLoop().schedule((Runnable) this::closeStreams,
                                                       closeTimeoutMillis,
                                                       TimeUnit.MILLISECONDS);
        }
    }

    public void closeStreams() {
        if (streamsClosed) {
            return;
        }
        streamsClosed = true;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        final Channel channel = ctx.log()
                                   .ensureAvailable(RequestLogProperty.SESSION)
                                   .channel();
        assert channel != null;
        WebSocketUtil.closeWebSocketInboundStream(channel);
        writer.close();
    }

    public void closeStreams(Throwable cause) {
        if (streamsClosed) {
            return;
        }
        streamsClosed = true;
        final Channel channel = ctx.log()
                                   .ensureAvailable(RequestLogProperty.SESSION)
                                   .channel();
        assert channel != null;
        WebSocketUtil.closeWebSocketInboundStream(channel, cause);
        writer.close(cause);
    }

    public boolean isCloseFrameSent() {
        return closeFrameSent;
    }
}

/*
 * Copyright 2016 LINE Corporation
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

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

public abstract class IdleTimeoutHandler extends IdleStateHandler {

    private static final Logger logger = LoggerFactory.getLogger(IdleTimeoutHandler.class);

    private final String name;
    private final boolean sendHttpPing;

    private boolean isHttp2;

    protected IdleTimeoutHandler(String name, long idleTimeoutMillis, boolean isHttp2, boolean sendHttpPing) {
        super(0, 0, idleTimeoutMillis, TimeUnit.MILLISECONDS);
        this.name = requireNonNull(name, "name");
        this.isHttp2 = isHttp2;
        this.sendHttpPing = sendHttpPing;
    }

    /**
     * If the channel is serving HTTP/2 and sendHttpPing is true then we will forward event to
     * {@link Http2KeepAliveHandler} to start sending PING's.
     * But if it is HTTP/1.1 channel then we will close the channel.
     */
    @Override
    protected final void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        if (isHttp2 && sendHttpPing) {
            ctx.fireUserEventTriggered(evt);
            return;
        }
        if (!evt.isFirst()) {
            return;
        }

        if (!hasRequestsInProgress(ctx)) {
            logger.debug("{} Closing an idle {} connection", ctx.channel(), name);
            ctx.channel().close();
        }
    }

    protected abstract boolean hasRequestsInProgress(ChannelHandlerContext ctx);

    public void setHttp2(boolean http2) {
        isHttp2 = http2;
    }
}

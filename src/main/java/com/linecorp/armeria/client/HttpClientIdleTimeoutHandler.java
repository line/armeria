/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

class HttpClientIdleTimeoutHandler extends IdleStateHandler {
    private static final Logger logger = LoggerFactory.getLogger(HttpClientIdleTimeoutHandler.class);

    /**
     * The number of requests that are waiting for the responses
     */
    protected int pendingResCount;

    HttpClientIdleTimeoutHandler(long idleTimeoutMillis) {
        this(idleTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    HttpClientIdleTimeoutHandler(long idleTimeout, TimeUnit timeUnit) {
        super(0, 0, idleTimeout, timeUnit);
    }

    boolean isRequestStart(Object msg) {
        return msg instanceof HttpRequest;
    }

    boolean isResponseEnd(Object msg) {
        return msg instanceof LastHttpContent;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (isResponseEnd(msg)) {
            pendingResCount--;
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (isRequestStart(msg)) {
            pendingResCount++;
        }

        super.write(ctx, msg, promise);
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        if (pendingResCount == 0 && evt.isFirst()) {
            logger.debug("{} Closing due to idleness", ctx.channel());
            ctx.close();
        }
    }
}

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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

interface HttpServer {

    @Nullable
    static HttpServer get(Channel channel) {
        final ChannelPipeline p = channel.pipeline();
        final ChannelHandler lastHandler = p.last();
        if (lastHandler instanceof HttpServer) {
            return (HttpServer) lastHandler;
        }

        for (ChannelHandler h : p.toMap().values()) {
            if (h instanceof HttpServer) {
                return (HttpServer) h;
            }
        }

        return null;
    }

    @Nullable
    static HttpServer get(ChannelHandlerContext ctx) {
        return get(ctx.channel());
    }

    SessionProtocol protocol();

    int unfinishedRequests();
}

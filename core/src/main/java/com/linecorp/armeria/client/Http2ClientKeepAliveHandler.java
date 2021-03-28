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

package com.linecorp.armeria.client;

import com.linecorp.armeria.internal.common.Http2KeepAliveHandler;

import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2FrameWriter;

final class Http2ClientKeepAliveHandler extends Http2KeepAliveHandler {
    Http2ClientKeepAliveHandler(Channel channel, Http2FrameWriter frameWriter, Timer keepAliveTimer,
                                long idleTimeoutMillis, long pingIntervalMillis,
                                long maxConnectionAgeMillis, int maxNumRequestsPerConnection) {

        super(channel, frameWriter, "client", keepAliveTimer,
              idleTimeoutMillis, pingIntervalMillis, maxConnectionAgeMillis, maxNumRequestsPerConnection);
    }

    @Override
    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
        return HttpSession.get(ctx.channel()).hasUnfinishedResponses();
    }
}

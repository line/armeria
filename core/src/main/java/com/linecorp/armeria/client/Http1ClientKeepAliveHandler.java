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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.client.UserAgentUtil;
import com.linecorp.armeria.internal.common.Http1KeepAliveHandler;

import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;

final class Http1ClientKeepAliveHandler extends Http1KeepAliveHandler {

    private static final RequestHeaders HTTP1_PING_REQUEST =
            RequestHeaders.builder(HttpMethod.OPTIONS, "*")
                          .set(HttpHeaderNames.USER_AGENT, UserAgentUtil.USER_AGENT.toString())
                          .build();

    private final HttpSession httpSession;
    private final Http1ResponseDecoder decoder;
    @Nullable
    private ClientHttp1ObjectEncoder encoder;

    Http1ClientKeepAliveHandler(Channel channel, Http1ResponseDecoder decoder,
                                Timer keepAliveTimer, long idleTimeoutMillis, long pingIntervalMillis,
                                long maxConnectionAgeMillis, int maxNumRequestsPerConnection,
                                boolean keepAliveOnPing) {
        super(channel, "client", keepAliveTimer, idleTimeoutMillis,
              pingIntervalMillis, maxConnectionAgeMillis, maxNumRequestsPerConnection, keepAliveOnPing);
        httpSession = HttpSession.get(requireNonNull(channel, "channel"));
        this.decoder = requireNonNull(decoder, "decoder");
    }

    void setEncoder(ClientHttp1ObjectEncoder encoder) {
        this.encoder = requireNonNull(encoder, "encoder");
    }

    @Override
    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
        final int id = httpSession.incrementAndGetNumRequestsSent();

        assert encoder != null;
        decoder.setPingReqId(id);
        final ChannelFuture future = encoder.writeHeaders(id, 0, HTTP1_PING_REQUEST, true, ctx.newPromise());
        ctx.flush();
        return future;
    }

    @Override
    protected boolean pingResetsPreviousPing() {
        return false;
    }

    @Override
    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
        return httpSession.hasUnfinishedResponses();
    }

    boolean isPing(int id) {
        return decoder.isPingReqId(id);
    }
}

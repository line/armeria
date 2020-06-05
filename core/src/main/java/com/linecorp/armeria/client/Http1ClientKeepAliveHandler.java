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

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.common.KeepAliveHandler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;

final class Http1ClientKeepAliveHandler extends KeepAliveHandler {

    private static final RequestHeaders HTTP1_PING_REQUEST = RequestHeaders.of(HttpMethod.OPTIONS, "*");

    private final HttpSession httpSession;
    private final ClientHttp1ObjectEncoder encoder;
    private final Http1ResponseDecoder decoder;

    Http1ClientKeepAliveHandler(Channel channel, ClientHttp1ObjectEncoder encoder, Http1ResponseDecoder decoder,
                                long idleTimeoutMillis, long pingIntervalMillis) {
        // TODO(ikhoon): Should set maxConnectionAgeMillis by https://github.com/line/armeria/pull/2741
        super(channel, "client", idleTimeoutMillis, pingIntervalMillis, /* maxConnectionAgeMillis */ 0);
        httpSession = HttpSession.get(requireNonNull(channel, "channel"));
        this.encoder = requireNonNull(encoder, "encoder");
        this.decoder = requireNonNull(decoder, "decoder");
    }

    @Override
    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
        final int id = httpSession.incrementAndGetNumRequestsSent();

        decoder.setPingReqId(id);
        final ChannelFuture future = encoder.writeHeaders(id, 0, HTTP1_PING_REQUEST, true);
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

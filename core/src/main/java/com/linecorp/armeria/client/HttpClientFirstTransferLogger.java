/*
 * Copyright 2018 LINE Corporation
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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

final class HttpClientFirstTransferLogger extends ChannelDuplexHandler {

    private final ClientRequestContext reqCtx;

    private boolean loggedResponseHeadersFirstBytesTransferred;
    private boolean loggedRequestHeadersFirstBytesTransferred;

    HttpClientFirstTransferLogger(ClientRequestContext reqCtx) {
        this.reqCtx = reqCtx;
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        if (!loggedResponseHeadersFirstBytesTransferred) {
            reqCtx.logBuilder().responseHeadersFirstBytesTransferred();
            loggedResponseHeadersFirstBytesTransferred = true;
        }
        super.read(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!loggedRequestHeadersFirstBytesTransferred) {
            reqCtx.logBuilder().requestHeadersFirstBytesTransferred();
            loggedRequestHeadersFirstBytesTransferred = true;
        }
        super.write(ctx, msg, promise);
    }
}

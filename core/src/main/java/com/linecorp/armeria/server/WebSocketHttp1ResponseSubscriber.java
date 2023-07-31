/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.armeria.internal.common.HttpHeadersUtil.mergeResponseHeaders;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;

import io.netty.channel.ChannelHandlerContext;

final class WebSocketHttp1ResponseSubscriber extends AbstractHttpResponseSubscriber {

    WebSocketHttp1ResponseSubscriber(ChannelHandlerContext ctx,
                                     ServerHttpObjectEncoder responseEncoder,
                                     DefaultServiceRequestContext reqCtx,
                                     DecodedHttpRequest req,
                                     CompletableFuture<Void> completionFuture) {
        super(ctx, responseEncoder, reqCtx, req, completionFuture);
        responseEncoder.keepAliveHandler().disconnectWhenFinished();
    }

    @Override
    void onResponseHeaders(ResponseHeaders headers) {
        final boolean endOfStream = headers.isEndOfStream();
        final ServerConfig config = reqCtx.config().server().config();
        final ResponseHeaders merged =
                mergeResponseHeaders(headers, reqCtx.additionalResponseHeaders(),
                                     reqCtx.config().defaultHeaders(),
                                     config.isServerHeaderEnabled(),
                                     config.isDateHeaderEnabled());
        logBuilder().responseHeaders(merged);
        setState(State.NEEDS_DATA);
        responseEncoder.writeHeaders(req.id(), req.streamId(), merged, endOfStream, reqCtx.method())
                       .addListener(writeHeadersFutureListener(endOfStream));
    }
}

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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.InboundTrafficController;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;

interface DecodedHttpRequest extends HttpRequest {

    static DecodedHttpRequest of(boolean endOfStream, EventLoop eventLoop, int id, int streamId,
                                 RequestHeaders headers, boolean keepAlive,
                                 InboundTrafficController inboundTrafficController,
                                 RoutingContext routingCtx, @Nullable Routed<ServiceConfig> routed) {
        if (endOfStream || routed == null) {
            return new EmptyContentDecodedHttpRequest(eventLoop, id, streamId, headers, keepAlive,
                                                      routingCtx, routed);
        } else {
            final ServiceConfig config = routed.value();
            final HttpService service = config.service();
            if (service.exchangeType(headers, routed.route()).isRequestStreaming()) {
                return new StreamingDecodedHttpRequest(eventLoop, id, streamId, headers, keepAlive,
                                                       inboundTrafficController,
                                                       config.maxRequestLength(), routingCtx, routed);
            } else {
                return new AggregatingDecodedHttpRequest(eventLoop, id, streamId, headers, keepAlive,
                                                         config.maxRequestLength(), routingCtx, routed);
            }
        }
    }

    int id();

    int streamId();

    /**
     * Returns whether to keep the connection alive after this request is handled.
     */
    boolean isKeepAlive();

    void init(ServiceRequestContext ctx);

    RoutingContext routingContext();

    /**
     * Returns the {@link ServiceConfig} mapped by {@link Routed}. {@code null} if a request path is invalid
     * or an {@code OPTION * HTTP/1.1} request.
     */
    @Nullable
    Routed<ServiceConfig> route();

    void close();

    void close(Throwable cause);

    /**
     * Sets the specified {@link HttpResponse} which responds to this request. This is always called
     * by the {@link HttpServerHandler} after the handler gets the {@link HttpResponse} from an
     * {@link HttpService}.
     */
    void setResponse(HttpResponse response);

    /**
     * Aborts the {@link HttpResponse} which responds to this request if it exists.
     *
     * @see Http2RequestDecoder#onRstStreamRead(ChannelHandlerContext, int, long)
     */
    void abortResponse(Throwable cause, boolean cancel);
}

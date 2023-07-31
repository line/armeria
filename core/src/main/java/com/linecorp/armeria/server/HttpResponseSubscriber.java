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

import static com.linecorp.armeria.internal.common.HttpHeadersUtil.CLOSE_STRING;
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.mergeResponseHeaders;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;

import io.netty.channel.ChannelHandlerContext;

final class HttpResponseSubscriber extends AbstractHttpResponseSubscriber {

    HttpResponseSubscriber(ChannelHandlerContext ctx, ServerHttpObjectEncoder responseEncoder,
                           DefaultServiceRequestContext reqCtx,
                           DecodedHttpRequest req, CompletableFuture<Void> completionFuture) {
        super(ctx, responseEncoder, reqCtx, req, completionFuture);
    }

    @Override
    void onResponseHeaders(ResponseHeaders headers) {
        boolean endOfStream = headers.isEndOfStream();
        final HttpStatus status = headers.status();
        final ResponseHeaders merged;
        if (status.isInformational()) {
            if (endOfStream) {
                req.abortResponse(new IllegalStateException(
                        "published an informational headers whose endOfStream is true: " + headers +
                        " (service: " + service() + ')'), true);
                return;
            }
            merged = headers;
        } else {
            if (req.method() == HttpMethod.HEAD) {
                endOfStream = true;
            } else {
                if (!reqCtx.additionalResponseTrailers().isEmpty()) {
                    endOfStream = false;
                }
                if (status.isContentAlwaysEmpty()) {
                    setState(State.NEEDS_TRAILERS);
                } else {
                    setState(State.NEEDS_DATA_OR_TRAILERS);
                }
            }
            if (endOfStream) {
                setDone(true);
            }
            final ServerConfig config = reqCtx.config().server().config();
            merged = mergeResponseHeaders(headers, reqCtx.additionalResponseHeaders(),
                                          reqCtx.config().defaultHeaders(),
                                          config.isServerHeaderEnabled(),
                                          config.isDateHeaderEnabled());
            final String connectionOption = merged.get(HttpHeaderNames.CONNECTION);
            if (CLOSE_STRING.equalsIgnoreCase(connectionOption)) {
                disconnectWhenFinished();
            }
            logBuilder().responseHeaders(merged);
        }

        responseEncoder.writeHeaders(req.id(), req.streamId(), merged,
                                     endOfStream, reqCtx.additionalResponseTrailers().isEmpty(), req.method())
                       .addListener(writeHeadersFutureListener(endOfStream));
    }
}

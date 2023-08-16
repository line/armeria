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

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.websocket.WebSocketClientUtil;

import io.netty.channel.EventLoop;

final class WebSocketHttp1ResponseWrapper extends HttpResponseWrapper {

    WebSocketHttp1ResponseWrapper(DecodedHttpResponse delegate,
                                  EventLoop eventLoop, ClientRequestContext ctx,
                                  long responseTimeoutMillis, long maxContentLength) {
        super(delegate, eventLoop, ctx, responseTimeoutMillis, maxContentLength);
        WebSocketClientUtil.setClosingResponseTask(ctx, cause -> {
            super.close(cause, false);
        });
    }

    @Override
    void close(@Nullable Throwable cause, boolean cancel) {
        if (cancel || !(cause instanceof ClosedSessionException)) {
            super.close(cause, cancel);
            return;
        }
        // Close the delegate directly so that we can give a chance to WebSocketFrameDecoder to close the
        // response normally if it receives a close frame before the ClosedSessionException is raised.
        delegate().close(cause);
    }
}

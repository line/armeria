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
package com.linecorp.armeria.internal.server.websocket;

import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameDecoder;
import com.linecorp.armeria.server.ServiceRequestContext;

final class WebSocketServiceFrameDecoder extends WebSocketFrameDecoder {

    private final ServiceRequestContext ctx;

    WebSocketServiceFrameDecoder(ServiceRequestContext ctx, int maxFramePayloadLength,
                                 boolean allowMaskMismatch, boolean aggregateContinuation) {
        super(maxFramePayloadLength, allowMaskMismatch, aggregateContinuation);
        this.ctx = ctx;
    }

    @Override
    protected boolean expectMaskedFrames() {
        return true;
    }

    @Override
    protected void onCloseFrameRead() {
        final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);
        assert ctxExtension != null;
        final Request request = ctxExtension.originalRequest();
        assert request instanceof HttpRequestWriter : request;
        //noinspection OverlyStrongTypeCast
        ((HttpRequestWriter) request).close();
    }
}

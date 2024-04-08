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
package com.linecorp.armeria.client.websocket;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.internal.client.websocket.WebSocketClientUtil;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameDecoder;

final class WebSocketClientFrameDecoder extends WebSocketFrameDecoder {

    private final ClientRequestContext ctx;

    WebSocketClientFrameDecoder(ClientRequestContext ctx, int maxFramePayloadLength,
                                boolean allowMaskMismatch, boolean aggregateContinuation) {
        super(maxFramePayloadLength, allowMaskMismatch, aggregateContinuation);
        this.ctx = ctx;
    }

    @Override
    protected boolean expectMaskedFrames() {
        return false;
    }

    @Override
    protected void onCloseFrameRead() {
        // Need to close the response when HTTP/1.1 is used.
        WebSocketClientUtil.closingResponse(ctx, null);
    }

    @Override
    protected void onProcessOnError(Throwable cause) {
        // Need to close the response when HTTP/1.1 is used.
        WebSocketClientUtil.closingResponse(ctx, cause);
    }
}

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
package com.linecorp.armeria.common.websocket;

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds a {@link WebSocketDecoderConfig}.
 */
@UnstableApi
public final class WebSocketDecoderConfigBuilder {

    static final int DEFAULT_MAX_FRAME_PAYLOAD_LENGTH = 65535; // 64 * 1024 -1

    private int maxFramePayloadLength = DEFAULT_MAX_FRAME_PAYLOAD_LENGTH;
    private boolean allowMaskMismatch;
    private boolean allowExtensions; //TODO(minwoox): support extensions

    WebSocketDecoderConfigBuilder() {}

    /**
     * Sets the maximum length of a frame's payload. If the size of a payload data exceeds the value,
     * {@link WebSocketCloseStatus#MESSAWebSocketFrameGE_TOO_BIG} is sent to the peer.
     */
    public WebSocketDecoderConfigBuilder maxFramePayloadLength(int maxFramePayloadLength) {
        checkArgument(maxFramePayloadLength > 0,
                      "maxFramePayloadLength: %s (expected: > 0)", maxFramePayloadLength);
        this.maxFramePayloadLength = maxFramePayloadLength;
        return this;
    }

    /**
     * Sets whether the decoder allow to loose the masking requirement on received frames.
     */
    public WebSocketDecoderConfigBuilder allowMaskMismatch(boolean allowMaskMismatch) {
        this.allowMaskMismatch = allowMaskMismatch;
        return this;
    }

    /**
     * Sets whether the decoder allow to use reserved extension bits. This is currently not supported.
     */
    public WebSocketDecoderConfigBuilder allowExtensions(boolean allowExtensions) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a newly-created {@link WebSocketDecoderConfig} with the properties set so far.
     */
    public WebSocketDecoderConfig build() {
        return new WebSocketDecoderConfig(maxFramePayloadLength, allowMaskMismatch,
                                          allowExtensions);
    }
}

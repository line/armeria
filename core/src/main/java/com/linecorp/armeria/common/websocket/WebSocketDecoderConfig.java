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
/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * {@link WebSocket} frames decoder configuration.
 */
@UnstableApi
public final class WebSocketDecoderConfig {

    /**
     * Returns a new {@link WebSocketDecoderConfigBuilder}.
     */
    public static WebSocketDecoderConfigBuilder builder() {
        return new WebSocketDecoderConfigBuilder();
    }

    private final int maxFramePayloadLength;
    private final boolean allowMaskMismatch;
    private final boolean allowExtensions;

    WebSocketDecoderConfig(int maxFramePayloadLength, boolean allowMaskMismatch,
                           boolean allowExtensions) {
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.allowMaskMismatch = allowMaskMismatch;
        this.allowExtensions = allowExtensions;
    }

    /**
     * Returns the maximum length of a frame's payload.
     */
    public int maxFramePayloadLength() {
        return maxFramePayloadLength;
    }

    /**
     * Tells whether this decoder allows to loosen the masking requirement on received frames.
     */
    public boolean allowMaskMismatch() {
        return allowMaskMismatch;
    }

    /**
     * Tells whether this decoder allow to use reserved extension bits.
     */
    public boolean allowExtensions() {
        return allowExtensions;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("maxFramePayloadLength", maxFramePayloadLength)
                          .add("allowMaskMismatch", allowMaskMismatch)
                          .add("allowExtensions", allowExtensions)
                          .toString();
    }
}

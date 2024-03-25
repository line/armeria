/*
 * Copyright 2022 LINE Corporation
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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represents the {@link WebSocketFrame} types.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.2">Base Framing Protocol, RFC 6455</a>
 */
@UnstableApi
public enum WebSocketFrameType {

    /**
     * A continuation frame.
     */
    CONTINUATION((byte) 0x0),

    /**
     * A text frame.
     */
    TEXT((byte) 0x1),

    /**
     * A binary frame.
     */
    BINARY((byte) 0x2),

    /**
     * A close frame.
     */
    CLOSE((byte) 0x8),

    /**
     * A ping frame.
     */
    PING((byte) 0x9),

    /**
     * A pong frame.
     */
    PONG((byte) 0xA);

    private final byte opcode;

    WebSocketFrameType(byte opcode) {
        this.opcode = opcode;
    }

    /**
     * Returns the opcode of the frame.
     */
    public byte opcode() {
        return opcode;
    }

    /**
     * Tells whether this frame is one of {@link #CLOSE}, {@link #PING}, and {@link #PONG}.
     */
    public boolean isControlFrame() {
        return opcode > 7;
    }
}

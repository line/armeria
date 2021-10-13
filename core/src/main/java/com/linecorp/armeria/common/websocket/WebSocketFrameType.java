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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represents the {@link WebSocketFrame} types.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.2">Base Framing Protocol</a>
 */
@UnstableApi
public enum WebSocketFrameType {

    CONTINUATION((byte) 0x0),

    TEXT((byte) 0x1),

    BINARY((byte) 0x2),

    CLOSE((byte) 0x8),

    PING((byte) 0x9),

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
     * Tells whether this frams is one of {@link #CLOSE}, {@link #PING}, and {@link #PONG}.
     */
    public boolean isControlFrame() {
        return opcode > 7;
    }
}

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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.ByteArrayBytes;

class ByteArrayWebSocketFrame extends ByteArrayBytes implements WebSocketFrame {

    private static final byte[] EMPTY_BYTES = {};

    static final WebSocketFrame EMPTY_PING = new ByteArrayWebSocketFrame(EMPTY_BYTES, WebSocketFrameType.PING);
    static final WebSocketFrame EMPTY_PONG = new ByteArrayWebSocketFrame(EMPTY_BYTES, WebSocketFrameType.PONG);

    private final WebSocketFrameType type;
    private final boolean finalFragment;

    @Nullable
    private String text;

    ByteArrayWebSocketFrame(byte[] array, WebSocketFrameType type) {
        this(array, type, true);
    }

    ByteArrayWebSocketFrame(byte[] array, WebSocketFrameType type, boolean finalFragment) {
        this(array, type, finalFragment, null);
    }

    ByteArrayWebSocketFrame(byte[] array, WebSocketFrameType type,
                            boolean finalFragment, @Nullable String text) {
        super(array);
        this.type = type;
        this.finalFragment = finalFragment;
        this.text = text;
    }

    @Override
    public WebSocketFrameType type() {
        return type;
    }

    @Override
    public boolean isFinalFragment() {
        return finalFragment;
    }

    @Override
    public String text() {
        if (text != null) {
            return text;
        }
        return text = toString(StandardCharsets.UTF_8);
    }

    @Override
    public int hashCode() {
        return (super.hashCode() * 31 + type.hashCode()) * 31 + Boolean.hashCode(finalFragment);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebSocketFrame)) {
            return false;
        }

        final WebSocketFrame that = (WebSocketFrame) o;
        if (length() != that.length()) {
            return false;
        }

        return type == that.type() &&
               finalFragment == that.isFinalFragment() &&
               Arrays.equals(array(), that.array());
    }

    @Override
    public String toString() {
        return toString(super.toString());
    }

    private String toString(String bytes) {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("type", type)
                          .add("finalFragment", finalFragment)
                          .add("bytes", bytes)
                          .toString();
    }
}

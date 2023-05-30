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

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.ByteBufBytes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

class ByteBufWebSocketFrame extends ByteBufBytes implements WebSocketFrame {

    private final WebSocketFrameType type;
    private final boolean finalFragment;

    @Nullable
    private String text;

    ByteBufWebSocketFrame(ByteBuf buf, WebSocketFrameType type, boolean finalFragment) {
        this(buf, true, type, finalFragment);
    }

    ByteBufWebSocketFrame(ByteBuf buf, boolean pooled, WebSocketFrameType type, boolean finalFragment) {
        super(buf, pooled);
        this.type = type;
        this.finalFragment = finalFragment;
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
    public boolean equals(Object obj) {
        if (!(obj instanceof WebSocketFrame)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final WebSocketFrame that = (WebSocketFrame) obj;
        if (length() != that.length()) {
            return false;
        }

        return type == that.type() &&
               finalFragment == that.isFinalFragment() &&
               ByteBufUtil.equals(buf(), that.byteBuf());
    }

    @Override
    public String toString() {
        return toString(super.toString());
    }

    private String toString(String bytes) {
        final ToStringHelper toStringHelper = MoreObjects.toStringHelper(this).omitNullValues();
        toStringHelper.add("type", type())
                      .add("finalFragment", finalFragment)
                      .add("bytes", bytes);
        addToString(toStringHelper);
        return toStringHelper.toString();
    }

    void addToString(ToStringHelper toStringHelper) {}
}

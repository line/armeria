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

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.buffer.ByteBuf;

/**
 * A {@link WebSocketFrame} that is used to send a text data.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
 */
@UnstableApi
public final class TextWebSocketFrame extends WebSocketFrameWrapper {

    @Nullable
    private String text;

    TextWebSocketFrame(String text, boolean finalFragment) {
        this(requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8), finalFragment);
        this.text = text;
    }

    TextWebSocketFrame(byte[] text, boolean finalFragment) {
        super(new ByteArrayWebSocketFrame(WebSocketFrameType.TEXT, text, finalFragment));
    }

    TextWebSocketFrame(ByteBuf binary, boolean finalFragment) {
        super(new ByteBufWebSocketFrame(WebSocketFrameType.TEXT, binary, finalFragment));
    }

    /**
     * Returns the text data in this frame.
     */
    public String text() {
        if (text != null) {
            return text;
        }

        final WebSocketFrame delegate = delegate();
        if (delegate.isPooled()) {
            return text = delegate.byteBuf().toString(StandardCharsets.UTF_8);
        }
        return text = new String(delegate.array());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(text) * 31 + super.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TextWebSocketFrame)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final TextWebSocketFrame that = (TextWebSocketFrame) obj;

        return Objects.equals(text, that.text()) && super.equals(obj);
    }

    @Override
    public String toString() {
        return toString(MoreObjects.toStringHelper(this))
                .add("text", text)
                .toString();
    }
}

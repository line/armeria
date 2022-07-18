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

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.Bytes;
import com.linecorp.armeria.internal.common.ByteArrayBytes;

import io.netty.buffer.ByteBuf;
import io.netty.util.ResourceLeakHint;

class DefaultWebSocketFrame implements WebSocketFrame, ResourceLeakHint {

    static final WebSocketFrame emptyPing = new DefaultWebSocketFrame(
            WebSocketFrameType.PING, ByteArrayBytes.empty());

    static final WebSocketFrame emptyPong = new DefaultWebSocketFrame(
            WebSocketFrameType.PONG, ByteArrayBytes.empty());

    private final WebSocketFrameType type;
    private final Bytes bytes;
    private final boolean finalFragment;
    private final boolean isText;
    private final boolean isBinary;

    DefaultWebSocketFrame(WebSocketFrameType type, Bytes bytes) {
        this(type, bytes, true, false, false);
    }

    DefaultWebSocketFrame(WebSocketFrameType type, Bytes bytes, boolean finalFragment,
                          boolean isText, boolean isBinary) {
        this.type = requireNonNull(type, "type");
        this.bytes = requireNonNull(bytes, "bytes");
        this.finalFragment = finalFragment;
        this.isText = isText;
        this.isBinary = isBinary;
    }

    @Override
    public final WebSocketFrameType type() {
        return type;
    }

    @Override
    public final boolean isFinalFragment() {
        return finalFragment;
    }

    @Override
    public boolean isText() {
        return isText;
    }

    @Override
    public boolean isBinary() {
        return isBinary;
    }

    @Override
    public String text() {
        return toString(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] array() {
        return bytes.array();
    }

    @Override
    public int length() {
        return bytes.length();
    }

    @Override
    public InputStream toInputStream() {
        return bytes.toInputStream();
    }

    @Override
    public boolean isPooled() {
        return bytes.isPooled();
    }

    @Override
    public ByteBuf byteBuf(ByteBufAccessMode mode) {
        return bytes.byteBuf(mode);
    }

    @Override
    public ByteBuf byteBuf(int offset, int length, ByteBufAccessMode mode) {
        return bytes.byteBuf(offset, length, mode);
    }

    @Override
    public void close() {
        bytes.close();
    }

    @Override
    public String toHintString() {
        if (bytes instanceof ResourceLeakHint) {
            return toString(((ResourceLeakHint) bytes).toHintString());
        }
        return toString(bytes.toString());
    }

    @Override
    public String toString(Charset charset) {
        return bytes.toString(charset);
    }

    @Override
    public String toString() {
        return toString(bytes.toString());
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

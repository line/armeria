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

import com.linecorp.armeria.common.BinaryData;
import com.linecorp.armeria.common.ByteBufAccessMode;

import io.netty.buffer.ByteBuf;
import io.netty.util.ResourceLeakHint;

class DefaultWebSocketFrame implements WebSocketFrame, ResourceLeakHint {

    private final WebSocketFrameType type;
    private final BinaryData binaryData;
    private final boolean finalFragment;
    private final boolean isText;
    private final boolean isBinary;

    DefaultWebSocketFrame(WebSocketFrameType type, BinaryData binaryData, boolean finalFragment,
                          boolean isText, boolean isBinary) {
        this.type = requireNonNull(type, "type");
        this.binaryData = requireNonNull(binaryData, "binaryData");
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
        return binaryData.array();
    }

    @Override
    public int length() {
        return binaryData.length();
    }

    @Override
    public InputStream toInputStream() {
        return binaryData.toInputStream();
    }

    @Override
    public boolean isPooled() {
        return binaryData.isPooled();
    }

    @Override
    public ByteBuf byteBuf(ByteBufAccessMode mode) {
        return binaryData.byteBuf(mode);
    }

    @Override
    public ByteBuf byteBuf(int offset, int length, ByteBufAccessMode mode) {
        return binaryData.byteBuf(offset, length, mode);
    }

    @Override
    public void close() {
        binaryData.close();
    }

    @Override
    public String toHintString() {
        if (binaryData instanceof ResourceLeakHint) {
            return toString(((ResourceLeakHint) binaryData).toHintString());
        }
        return toString(binaryData.toString());
    }

    @Override
    public String toString(Charset charset) {
        return binaryData.toString(charset);
    }

    @Override
    public String toString() {
        return toString(binaryData.toString());
    }

    private String toString(String binaryDataString) {
        final ToStringHelper toStringHelper = MoreObjects.toStringHelper(this).omitNullValues();
        toStringHelper.add("type", type())
                      .add("finalFragment", finalFragment)
                      .add("binaryData", binaryDataString);
        addToString(toStringHelper);
        return toStringHelper.toString();
    }

    void addToString(ToStringHelper toStringHelper) {}
}

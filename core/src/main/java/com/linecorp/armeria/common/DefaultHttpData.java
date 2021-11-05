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
package com.linecorp.armeria.common;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.internal.common.ByteArrayBinaryData;
import com.linecorp.armeria.internal.common.ByteBufBinaryData;

import io.netty.buffer.ByteBuf;
import io.netty.util.ResourceLeakHint;

/**
 * A {@code byte[]}-based {@link HttpData}.
 */
final class DefaultHttpData implements HttpData, ResourceLeakHint {

    static final DefaultHttpData empty = new DefaultHttpData(ByteArrayBinaryData.empty(), false);
    static final DefaultHttpData emptyEos = new DefaultHttpData(ByteArrayBinaryData.empty(), true);

    static HttpData of(byte[] binaryData) {
        return new DefaultHttpData(new ByteArrayBinaryData(binaryData));
    }

    static HttpData of(ByteBuf binaryData, boolean pooled) {
        return new DefaultHttpData(new ByteBufBinaryData(binaryData, pooled));
    }

    private final BinaryData binaryData;
    private final boolean endOfStream;

    private DefaultHttpData(BinaryData binaryData) {
        this(binaryData, false);
    }

    private DefaultHttpData(BinaryData binaryData, boolean endOfStream) {
        this.binaryData = binaryData;
        this.endOfStream = endOfStream;
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

    private String toString(String binaryData) {
        return MoreObjects.toStringHelper(this)
                          .add("endOfStream", endOfStream)
                          .add("binaryData", binaryData)
                          .toString();
    }

    @Override
    public InputStream toInputStream() {
        return binaryData.toInputStream();
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @Override
    public HttpData withEndOfStream(boolean endOfStream) {
        if (isEndOfStream() == endOfStream) {
            return this;
        }

        if (isEmpty()) {
            return endOfStream ? emptyEos : empty;
        }

        return new DefaultHttpData(binaryData, endOfStream);
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
    public int hashCode() {
        return Objects.hash(endOfStream, binaryData);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DefaultHttpData)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final DefaultHttpData that = (DefaultHttpData) obj;
        if (length() != that.length()) {
            return false;
        }

        return endOfStream == that.isEndOfStream() && binaryData.equals(that.binaryData);
    }
}

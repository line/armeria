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

import com.linecorp.armeria.internal.common.ByteArrayBytes;
import com.linecorp.armeria.internal.common.ByteBufBytes;

import io.netty.buffer.ByteBuf;
import io.netty.util.ResourceLeakHint;

/**
 * A {@code byte[]}-based {@link HttpData}.
 */
final class DefaultHttpData implements HttpData, ResourceLeakHint {

    static final DefaultHttpData empty = new DefaultHttpData(ByteArrayBytes.empty(), false);
    static final DefaultHttpData emptyEos = new DefaultHttpData(ByteArrayBytes.empty(), true);

    static HttpData of(byte[] data) {
        return new DefaultHttpData(ByteArrayBytes.of(data));
    }

    static HttpData of(ByteBuf data, boolean pooled) {
        return new DefaultHttpData(ByteBufBytes.of(data, pooled));
    }

    private final Bytes bytes;
    private final boolean endOfStream;

    private DefaultHttpData(Bytes bytes) {
        this(bytes, false);
    }

    private DefaultHttpData(Bytes bytes, boolean endOfStream) {
        this.bytes = bytes;
        this.endOfStream = endOfStream;
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
        return MoreObjects.toStringHelper(this)
                          .add("endOfStream", endOfStream)
                          .add("bytes", bytes)
                          .toString();
    }

    @Override
    public InputStream toInputStream() {
        return bytes.toInputStream();
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

        return new DefaultHttpData(bytes, endOfStream);
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
    public int hashCode() {
        return Objects.hash(endOfStream, bytes);
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

        return endOfStream == that.isEndOfStream() && bytes.equals(that.bytes);
    }
}

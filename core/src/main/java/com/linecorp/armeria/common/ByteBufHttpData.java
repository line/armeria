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

package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.ByteArrayHttpData.EMPTY;
import static com.linecorp.armeria.common.ByteArrayHttpData.EMPTY_EOS;

import java.io.InputStream;
import java.nio.charset.Charset;

import com.linecorp.armeria.internal.common.ByteBufBytes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.ResourceLeakHint;

final class ByteBufHttpData extends ByteBufBytes implements HttpData {

    ByteBufHttpData(ByteBuf buf, boolean pooled) {
        super(buf, pooled);
    }

    @Override
    public HttpData withEndOfStream(boolean endOfStream) {
        if (!endOfStream) {
            return this;
        }

        if (isEmpty()) {
            return EMPTY_EOS;
        }

        return new EndOfStreamByteBufHttpData(this);
    }

    @Override
    public boolean isEndOfStream() {
        return false;
    }

    @SuppressWarnings("RedundantMethodOverride")
    @Override
    public int hashCode() {
        // Use hashcode in ByteBufBytes because we don't use endOfStream in equals.
        return super.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof HttpData)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final HttpData that = (HttpData) obj;
        if (length() != that.length()) {
            return false;
        }

        return ByteBufUtil.equals(buf(), that.byteBuf());
    }

    private static class EndOfStreamByteBufHttpData implements ResourceLeakHint, HttpData {

        private final ByteBufHttpData delegate;

        EndOfStreamByteBufHttpData(ByteBufHttpData delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte[] array() {
            return delegate.array();
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public String toString(Charset charset) {
            return delegate.toString(charset);
        }

        @Override
        public String toString() {
            return delegate + ", {EOS}";
        }

        @Override
        public InputStream toInputStream() {
            return delegate.toInputStream();
        }

        @Override
        public boolean isPooled() {
            return delegate.isPooled();
        }

        @Override
        public ByteBuf byteBuf(ByteBufAccessMode mode) {
            return delegate.byteBuf(mode);
        }

        @Override
        public ByteBuf byteBuf(int offset, int length, ByteBufAccessMode mode) {
            return delegate.byteBuf(offset, length, mode);
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public HttpData withEndOfStream(boolean endOfStream) {
            if (endOfStream) {
                return this;
            }
            if (isEmpty()) {
                return EMPTY;
            }
            return delegate;
        }

        @Override
        public boolean isEndOfStream() {
            return true;
        }

        @Override
        public String toHintString() {
            return delegate.toHintString();
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
        @Override
        public boolean equals(Object obj) {
            return delegate.equals(obj);
        }
    }
}

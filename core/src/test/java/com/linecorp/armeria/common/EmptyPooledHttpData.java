/*
 * Copyright 2020 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.internal.EmptyArrays;

/**
 * A pooled {@link HttpData} whose content is always empty. Used when testing if an empty reference-counted
 * data is released by the callee.
 */
final class EmptyPooledHttpData implements HttpData {

    private final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
    private boolean closed;

    int refCnt() {
        return buf.refCnt();
    }

    @Override
    public byte[] array() {
        return EmptyArrays.EMPTY_BYTES;
    }

    @Override
    public int length() {
        return 0;
    }

    @Override
    public HttpData withEndOfStream(boolean endOfStream) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEndOfStream() {
        return false;
    }

    @Override
    public String toString(Charset charset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream toInputStream() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isPooled() {
        return true;
    }

    @Override
    public ByteBuf byteBuf(ByteBufAccessMode mode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ByteBuf byteBuf(int offset, int length, ByteBufAccessMode mode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        assertThat(closed).isFalse();
        closed = true;
        buf.release();
    }
}

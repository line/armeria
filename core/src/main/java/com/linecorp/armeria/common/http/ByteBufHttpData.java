/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

/**
 * A {@link HttpData} that is backed by a {@link ByteBuf}. Due to the complexity of reference counting, it is
 * recommended to only use this in advanced use cases.
 */
public class ByteBufHttpData implements HttpData, AutoCloseable {

    private final ByteBuf buf;
    private final boolean isEndOfStream;

    /**
     * Construct a new {@link ByteBufHttpData}. Ownership of {@code buf} is taken by this
     * {@link ByteBufHttpData}.
     */
    public ByteBufHttpData(ByteBuf buf, boolean isEndOfStream) {
        this.buf = buf;
        this.isEndOfStream = isEndOfStream;
    }

    /**
     * Returns the underlying {@link ByteBuf}.
     */
    public ByteBuf buf() {
        return buf;
    }

    @Override
    public boolean isEndOfStream() {
        return isEndOfStream;
    }

    @Override
    public byte[] array() {
        return ByteBufUtil.getBytes(buf);
    }

    @Override
    public int offset() {
        return 0;
    }

    @Override
    public int length() {
        return buf.readableBytes();
    }

    @Override
    public String toString(Charset charset) {
        throw new UnsupportedOperationException("ByteBufHttpData does not support string conversion.");
    }

    @Override
    public String toStringUtf8() {
        throw new UnsupportedOperationException("ByteBufHttpData does not support string conversion.");
    }

    @Override
    public String toStringAscii() {
        throw new UnsupportedOperationException("ByteBufHttpData does not support string conversion.");
    }

    @Override
    public void close() throws Exception {
        buf.release();
    }
}

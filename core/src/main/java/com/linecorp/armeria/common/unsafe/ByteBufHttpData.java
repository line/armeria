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
package com.linecorp.armeria.common.unsafe;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.AbstractHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

final class ByteBufHttpData extends AbstractHttpData implements PooledHttpData {

    private static final AtomicIntegerFieldUpdater<ByteBufHttpData>
            closedUpdater = AtomicIntegerFieldUpdater.newUpdater(ByteBufHttpData.class, "closed");

    static final ByteBufHttpData EMPTY = new ByteBufHttpData(false);
    @VisibleForTesting
    static final ByteBufHttpData EMPTY_EOS = new ByteBufHttpData(true);

    private final ByteBuf buf;
    private final int baseIndex;
    private final int length;
    private final boolean endOfStream;

    @SuppressWarnings("FieldMayBeFinal") // Updated via `closedUpdater`
    private volatile int closed;

    /**
     * Constructs a new {@link ByteBufHttpData}. Ownership of {@code buf} is taken by this
     * {@link ByteBufHttpData}, which must not be mutated anymore.
     */
    ByteBufHttpData(ByteBuf buf, boolean endOfStream) {
        this(buf, buf.readerIndex(), buf.readableBytes(), endOfStream);
    }

    private ByteBufHttpData(ByteBuf buf, int baseIndex, int length, boolean endOfStream) {
        assert baseIndex >= 0 && length > 0 && (baseIndex + length) <= buf.capacity()
                : "baseIndex: " + baseIndex + ", length: " + length + ", buf: " + buf;

        this.baseIndex = baseIndex;
        this.length = length;
        this.buf = buf;
        this.endOfStream = endOfStream;
    }

    /**
     * Creates an empty data.
     */
    private ByteBufHttpData(boolean endOfStream) {
        buf = Unpooled.EMPTY_BUFFER;
        baseIndex = 0;
        length = 0;
        this.endOfStream = endOfStream;
        closed = -1; // Never closed
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @Override
    public byte[] array() {
        if (baseIndex == 0 && buf.hasArray() && buf.arrayOffset() == 0) {
            final byte[] array = buf.array();
            if (array.length == length) {
                return array;
            }
        }

        return ByteBufUtil.getBytes(buf, baseIndex, length);
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public boolean isEmpty() {
        return length == 0;
    }

    @Override
    public int refCnt() {
        return buf.refCnt();
    }

    @Override
    public PooledHttpData retain() {
        buf.retain();
        return this;
    }

    @Override
    public PooledHttpData retain(int increment) {
        buf.retain(increment);
        return this;
    }

    @Override
    public PooledHttpData touch() {
        buf.touch();
        return this;
    }

    @Override
    public PooledHttpData touch(Object hint) {
        buf.touch(hint);
        return this;
    }

    @Override
    public boolean release() {
        return buf.release();
    }

    @Override
    public boolean release(int decrement) {
        return buf.release(decrement);
    }

    @Override
    public ByteBuf content() {
        buf.touch();
        return buf;
    }

    @Override
    public PooledHttpData copy() {
        if (isEmpty()) {
            return this;
        }
        return replace0(buf.copy(baseIndex, length));
    }

    @Override
    public PooledHttpData duplicate() {
        if (isEmpty()) {
            return this;
        }
        return replace0(buf.slice(baseIndex, length));
    }

    @Override
    public PooledHttpData retainedDuplicate() {
        if (isEmpty()) {
            return this;
        }
        return replace0(buf.retainedSlice(baseIndex, length));
    }

    @Override
    public PooledHttpData replace(ByteBuf content) {
        requireNonNull(content, "content");
        content.touch();
        return replace0(content);
    }

    private PooledHttpData replace0(ByteBuf content) {
        return PooledHttpData.wrap(content).withEndOfStream(endOfStream);
    }

    @Override
    protected byte getByte(int index) {
        return buf.getByte(baseIndex + index);
    }

    @Override
    public String toString(Charset charset) {
        return buf.toString(baseIndex, length, charset);
    }

    @Override
    public String toString() {
        return "ByteBufHttpData{" + buf + '}';
    }

    @Override
    public InputStream toInputStream() {
        return new ByteBufInputStream(buf.slice(baseIndex, length), false);
    }

    @Override
    public PooledHttpData withEndOfStream(boolean endOfStream) {
        if (isEmpty()) {
            return endOfStream ? EMPTY_EOS : EMPTY;
        }

        if (endOfStream == this.endOfStream) {
            return this;
        }

        return new ByteBufHttpData(buf.touch(), baseIndex, length, endOfStream);
    }

    @Override
    public void close() {
        if (!closedUpdater.compareAndSet(this, 0, 1)) {
            return;
        }
        release();
    }
}

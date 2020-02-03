/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.unsafe;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.nio.charset.Charset;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.AbstractHttpData;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.UnstableApi;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/**
 * An {@link HttpData} that is backed by a {@link ByteBuf} for optimizing certain internal use cases. Not for
 * general use.
 */
@UnstableApi
public final class ByteBufHttpData extends AbstractHttpData implements ByteBufHolder {

    private final ByteBuf buf;
    private final boolean endOfStream;
    private final int length;

    /**
     * Constructs a new {@link ByteBufHttpData}. Ownership of {@code buf} is taken by this
     * {@link ByteBufHttpData}, which must not be mutated anymore.
     */
    public ByteBufHttpData(ByteBuf buf, boolean endOfStream) {
        length = requireNonNull(buf, "buf").readableBytes();
        if (length != 0) {
            this.buf = buf;
        } else {
            buf.release();
            this.buf = Unpooled.EMPTY_BUFFER;
        }
        this.endOfStream = endOfStream;
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @Override
    public byte[] array() {
        if (buf.hasArray() && buf.arrayOffset() == 0 && buf.array().length == length) {
            return buf.array();
        } else {
            return ByteBufUtil.getBytes(buf);
        }
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public boolean isEmpty() {
        buf.touch();
        return super.isEmpty();
    }

    @Override
    public int refCnt() {
        return buf.refCnt();
    }

    @Override
    public ByteBufHttpData retain() {
        buf.retain();
        return this;
    }

    @Override
    public ByteBufHttpData retain(int increment) {
        buf.retain(increment);
        return this;
    }

    @Override
    public ByteBufHttpData touch() {
        buf.touch();
        return this;
    }

    @Override
    public ByteBufHttpData touch(Object hint) {
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
    public ByteBufHttpData copy() {
        return new ByteBufHttpData(buf.copy(), endOfStream);
    }

    @Override
    public ByteBufHttpData duplicate() {
        return new ByteBufHttpData(buf.duplicate(), endOfStream);
    }

    @Override
    public ByteBufHttpData retainedDuplicate() {
        return new ByteBufHttpData(buf.retainedDuplicate(), endOfStream);
    }

    @Override
    public ByteBufHttpData replace(ByteBuf content) {
        requireNonNull(content, "content");
        content.touch();
        return new ByteBufHttpData(content, endOfStream);
    }

    @Override
    protected byte getByte(int index) {
        return buf.getByte(index);
    }

    @Override
    public String toString(Charset charset) {
        return buf.toString(charset);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("buf", buf.toString()).toString();
    }

    @Override
    public InputStream toInputStream() {
        return new ByteBufInputStream(buf.retainedDuplicate(), true);
    }

    @Override
    public ByteBufHttpData withEndOfStream() {
        return new ByteBufHttpData(buf, true);
    }
}

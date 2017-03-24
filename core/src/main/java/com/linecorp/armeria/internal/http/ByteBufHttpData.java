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

package com.linecorp.armeria.internal.http;

import java.nio.charset.Charset;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.http.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.DefaultByteBufHolder;

/**
 * A {@link HttpData} that is backed by a {@link ByteBuf} for optimizing certain internal use cases. Not for
 * general use.
 */
public class ByteBufHttpData extends DefaultByteBufHolder implements InternalHttpData {

    private final boolean endOfStream;

    /**
     * Construct a new {@link ByteBufHttpData}. Ownership of {@code buf} is taken by this
     * {@link ByteBufHttpData}.
     */
    public ByteBufHttpData(ByteBuf buf, boolean endOfStream) {
        super(buf);
        this.endOfStream = endOfStream;
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @Override
    public byte[] array() {
        if (content().hasArray()) {
            return content().array();
        } else {
            return ByteBufUtil.getBytes(content());
        }
    }

    @Override
    public int offset() {
        return content().hasArray() ? content().arrayOffset() : 0;
    }

    @Override
    public int length() {
        return content().readableBytes();
    }

    @Override
    public int hashCode() {
        return content().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return equalTo(obj);
    }

    @Override
    public String toString(Charset charset) {
        return content().toString(charset);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("buf", contentToString()).toString();
    }

    @Override
    public byte getByte(int index) {
        return content().getByte(index);
    }
}

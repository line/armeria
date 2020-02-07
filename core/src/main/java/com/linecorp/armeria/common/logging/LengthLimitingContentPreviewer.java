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

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.math.IntMath;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

abstract class LengthLimitingContentPreviewer implements ContentPreviewer {

    private static final ByteBuf[] BYTE_BUFS = new ByteBuf[0];

    private final List<ByteBuf> bufferList = new ArrayList<>();
    private final int maxLength;
    private final int inflatedMaxLength;

    @Nullable
    private String produced;
    private int aggregatedLength;

    LengthLimitingContentPreviewer(int maxLength, @Nullable Charset charset) {
        this.maxLength = maxLength;
        inflatedMaxLength = inflateMaxLength(maxLength, charset);
    }

    private static int inflateMaxLength(int maxLength, @Nullable Charset charset) {
        if (charset == null) {
            return maxLength;
        }
        final long maxBytesPerChar = (long) Math.ceil(CharsetUtil.encoder(charset).maxBytesPerChar());
        return Ints.saturatedCast(maxBytesPerChar * maxLength);
    }

    @Override
    public void onData(HttpData data) {
        requireNonNull(data, "data");
        if (data.isEmpty()) {
            return;
        }
        final int length = Math.min(inflatedMaxLength - aggregatedLength, data.length());
        bufferList.add(duplicateData(data, length));

        aggregatedLength = IntMath.saturatedAdd(aggregatedLength, length);
        if (aggregatedLength >= inflatedMaxLength || data.isEndOfStream()) {
            produce();
        }
    }

    private static ByteBuf duplicateData(HttpData httpData, int length) {
        if (httpData instanceof ByteBufHolder) {
            final ByteBuf content = ((ByteBufHolder) httpData).content();
            if (content.readableBytes() == length) {
                return content.retainedDuplicate();
            }
            return content.retainedSlice(content.readerIndex(), length);
        } else {
            return Unpooled.wrappedBuffer(httpData.array(), 0, length);
        }
    }

    @Override
    public String produce() {
        if (produced != null) {
            return produced;
        }
        try {
            final String produced = produce(Unpooled.wrappedBuffer(bufferList.toArray(BYTE_BUFS)));
            return this.produced = produced.length() > maxLength ? produced.substring(0, maxLength) : produced;
        } finally {
            bufferList.forEach(ReferenceCountUtil::safeRelease);
            bufferList.clear();
        }
    }

    abstract String produce(ByteBuf wrappedBuffer);
}

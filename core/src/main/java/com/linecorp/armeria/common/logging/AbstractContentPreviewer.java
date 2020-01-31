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

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.math.IntMath;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

abstract class AbstractContentPreviewer implements ContentPreviewer {

    private static final ByteBuf[] BYTE_BUFS = new ByteBuf[0];

    private final List<ByteBuf> bufferList = new ArrayList<>();
    private final int maxLength;

    @Nullable
    private String produced;
    private int aggregatedLength;

    AbstractContentPreviewer(int maxLength) {
        this.maxLength = maxLength;
    }

    @Override
    public void onData(HttpData data) {
        requireNonNull(data, "data");
        if (data.isEmpty()) {
            return;
        }
        final int length = Math.min(maxLength - aggregatedLength, data.length());
        bufferList.add(duplicateData(data, length));

        aggregatedLength = IntMath.saturatedAdd(aggregatedLength, length);
        if (aggregatedLength >= maxLength || data.isEndOfStream()) {
            produce();
        }
    }

    private static ByteBuf duplicateData(HttpData httpData, int length) {
        if (httpData instanceof ByteBufHolder) {
            final ByteBuf content = ((ByteBufHolder) httpData).content();
            if (content.readableBytes() == length) {
                // No need to slice.
                return content.retainedDuplicate();
            }
            return content.retainedSlice(content.readerIndex(), length);
        } else {
            return Unpooled.wrappedBuffer(httpData.array());
        }
    }

    @Override
    public String produce() {
        if (produced != null) {
            return produced;
        }
        try {
            return produced = produce(Unpooled.wrappedBuffer(bufferList.toArray(BYTE_BUFS)));
        } finally {
            bufferList.forEach(ReferenceCountUtil::safeRelease);
            bufferList.clear();
        }
    }

    abstract String produce(ByteBuf wrappedBuffer);
}

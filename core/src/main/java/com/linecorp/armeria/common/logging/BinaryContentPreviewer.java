/*
 * Copyright 2019 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.google.common.math.IntMath;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

abstract class BinaryContentPreviewer implements ContentPreviewer {

    private static final ByteBuf[] BYTE_BUFS = new ByteBuf[0];
    private final List<ByteBuf> bufferList;
    @Nullable
    private HttpHeaders headers;
    @Nullable
    private String produced;
    private int aggregatedLength;
    private int maxAggregatedLength;

    BinaryContentPreviewer(int maxAggregatedLength) {
        checkArgument(maxAggregatedLength >= 0,
                      "maxAggregatedLength: %s (expected: >= 0)", maxAggregatedLength);
        this.maxAggregatedLength = maxAggregatedLength;
        bufferList = new ArrayList<>();
    }

    void maxAggregatedLength(int length) {
        assert maxAggregatedLength == 0 : "maxAggregatedLength() should not be called more than once.";
        checkArgument(length > 0,
                      "length: %s (expected: > 0", length);
        maxAggregatedLength = length;
    }

    static ContentPreviewer create(int maxAggregatedLength,
                                   BiFunction<? super HttpHeaders, ? super ByteBuf, String> reproducer) {
        requireNonNull(reproducer, "reproducer");
        checkArgument(maxAggregatedLength > 0, "maxAggregatedLength: %s (expected: > 0)", maxAggregatedLength);
        return new BinaryContentPreviewer(maxAggregatedLength) {
            @Override
            protected String reproduce(HttpHeaders headers, ByteBuf wrappedBuffer) {
                return reproducer.apply(headers, wrappedBuffer);
            }
        };
    }

    @Override
    public void onHeaders(HttpHeaders headers) {
        assert this.headers == null : "onHeaders() has been already invoked.";
        this.headers = headers;
    }

    private static ByteBuf duplicateData(HttpData httpData, int length) {
        checkArgument(length > 0 && length <= httpData.length(),
                      "length: %s, HttpData.length(): %s (expected: 0 < length <= HttpData.length())",
                      length, httpData.length());
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
    public void onData(HttpData data) {
        assert maxAggregatedLength > 0 : "maxAggregatedLength() should be called before onData().";
        if (data.isEmpty()) {
            return;
        }
        final int length = Math.min(maxAggregatedLength - aggregatedLength, data.length());
        bufferList.add(duplicateData(data, length));

        aggregatedLength = IntMath.saturatedAdd(aggregatedLength, length);

        if (aggregatedLength >= maxAggregatedLength || data.isEndOfStream()) {
            produce();
        }
    }

    @Override
    public boolean isDone() {
        return produced != null;
    }

    protected abstract String reproduce(HttpHeaders headers, ByteBuf wrappedBuffer);

    @Override
    public String produce() {
        assert headers != null : "headers has not been initialized yet.";
        if (produced != null) {
            return produced;
        }
        try {
            return produced = reproduce(headers, Unpooled.wrappedBuffer(bufferList.toArray(BYTE_BUFS)));
        } finally {
            bufferList.forEach(ReferenceCountUtil::safeRelease);
            bufferList.clear();
        }
    }
}

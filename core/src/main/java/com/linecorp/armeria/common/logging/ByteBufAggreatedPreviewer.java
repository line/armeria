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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

abstract class ByteBufAggreatedPreviewer implements ContentPreviewer {

    public static final ByteBuf[] BYTE_BUFS = new ByteBuf[0];
    private int capacity;
    private final List<ByteBuf> bufferList;
    @Nullable
    private HttpHeaders headers;
    @Nullable
    private String produced;
    private int aggregatedLength;

    ByteBufAggreatedPreviewer(int capacity) {
        this.capacity = capacity;
        bufferList = new ArrayList<>();
    }

    ByteBufAggreatedPreviewer() {
        bufferList = new ArrayList<>();
        capacity = 0;
    }

    void increaseCapacity(int delta) {
        if (Integer.MAX_VALUE - delta >= capacity) {
            capacity = Integer.MAX_VALUE;
        } else {
            capacity += delta;
        }
    }

    static ContentPreviewer create(int capacity, BiFunction<HttpHeaders, ByteBuf, String> reproducer) {
        return new ByteBufAggreatedPreviewer(capacity) {
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

    @Override
    public void onData(HttpData data) {
        if (data.isEmpty()) {
            return;
        }
        final ByteBuf newBuffer;
        if (data instanceof ByteBufHolder) {
            newBuffer = ((ByteBufHolder) data).content();
        } else {
            newBuffer = Unpooled.wrappedBuffer(data.array(), data.offset(), data.length());
        }
        aggregatedLength += data.length();
        bufferList.add(newBuffer.retain());
        if (aggregatedLength >= capacity) {
            produce();
        }
    }

    @Override
    public boolean isDone() {
        return produced != null || aggregatedLength >= capacity;
    }

    protected abstract String reproduce(HttpHeaders headers, ByteBuf wrappedBuffer);

    @Override
    public String produce() {
        assert headers != null : "headers has not been initialized yet.";
        if (produced != null) {
            return produced;
        }
        try {
            return produced = reproduce(headers, Unpooled.wrappedBuffer(bufferList.toArray(
                    BYTE_BUFS)).asReadOnly());
        } finally {
            bufferList.forEach(ReferenceCountUtil::safeRelease);
            bufferList.clear();
        }
    }
}

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

import java.nio.charset.Charset;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.buffer.ByteBuf;

public interface ContentPreviewWriter {
    void write(HttpHeaders headers, HttpData data);

    String produce();

    ContentPreviewWriter EMPTY = new ContentPreviewWriter() {
        @Override
        public void write(HttpHeaders headers, HttpData data) { }

        @Override
        public String produce() {
            return "";
        }
    };

    static ContentPreviewWriter ofByteBuf(int length, Function<ByteBuf, String> reproducer) {
        return new ByteBufAggreatedWriter(length, (headers, buffer) -> reproducer.apply(buffer));
    }

    static ContentPreviewWriter ofString(int length, Charset defaultCharset) {
        return new StringAggregatedWriter(length, defaultCharset);
    }

    static ContentPreviewWriter ofString(int length) {
        return ofString(length, Charset.defaultCharset());
    }

    static ContentPreviewWriter of() {
        return EMPTY;
    }
}

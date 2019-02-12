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
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

import io.netty.buffer.ByteBuf;

public interface ContentPreviewer {
    void onData(HttpData data);

    String produce();

    default void onHeaders(HttpHeaders headers) {}

    ContentPreviewer DISABLED = new ContentPreviewer() {

        @Override
        public void onData(HttpData data) {}

        @Override
        public String produce() {
            return null;
        }
    };

    /**
     * TODO: Add Javadocs.
     */
    static ContentPreviewer ofBinary(int length, Function<ByteBuf, String> reproducer) {
        return ofBinary(length, (headers, buffer) -> reproducer.apply(buffer));
    }

    /**
     * TODO: Add Javadocs.
     */
    static ContentPreviewer ofBinary(int length, BiFunction<HttpHeaders, ByteBuf, String> reproducer) {
        return new ByteBufAggreatedPreviewer(length, reproducer);
    }

    /**
     * TODO: Add Javadocs.
     */
    static ContentPreviewer ofText(int length, Charset defaultCharset) {
        return new StringAggregatedPreviewer(length, defaultCharset);
    }

    /**
     * TODO: Add Javadocs.
     */
    static ContentPreviewer ofText(int length) {
        return ofText(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    /**
     * TODO: Add Javadocs.
     */
    static ContentPreviewer disabled() {
        return DISABLED;
    }
}

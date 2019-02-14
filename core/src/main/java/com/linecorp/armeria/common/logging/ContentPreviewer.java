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

    /**
     * Creates a new instance of {@link ContentPreviewer} which produces the preview through {@code reproducer}
     * when the contents has been aggregated more than {@code length} bytes.
     */
    static ContentPreviewer ofBinary(int length, Function<ByteBuf, String> reproducer) {
        return ofBinary(length, (headers, buffer) -> reproducer.apply(buffer));
    }

    /**
     * Creates a new instance of {@link ContentPreviewer} which produces the preview through {@code reproducer}
     * when the contents has been aggregated more than {@code length} bytes.
     */
    static ContentPreviewer ofBinary(int length, BiFunction<HttpHeaders, ByteBuf, String> reproducer) {
        return ByteBufAggreatedPreviewer.create(length, reproducer);
    }

    /**
     * Creates a new instance of {@link ContentPreviewer} which produces the text
     * with the maximum {@code length} limit.
     * Note that {@code defaultCharset} will be applied
     * if the charset has not been specified in {@code "Content-Type"} header.
     */
    static ContentPreviewer ofText(int length, Charset defaultCharset) {
        return new StringAggregatedPreviewer(length, defaultCharset);
    }

    /**
     * Creates a new instance of {@link ContentPreviewer} which produces the text
     * with the maximum {@code length} limit.
     * Note that {@link ArmeriaHttpUtil#HTTP_DEFAULT_CONTENT_CHARSET} will be applied
     * if the charset has not been specified in {@code "Content-Type"} header.
     */
    static ContentPreviewer ofText(int length) {
        return ofText(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    static ContentPreviewer disabled() {
        return NoopContentPreviewer.INSTANCE;
    }

    /**
     * Invoked after request/response headers is received.
     */
    void onHeaders(HttpHeaders headers);

    /**
     * Invoked after request/response data is received.
     * Note that it is not invoked when the request/response is completed or {@link #isDone()} returns
     * {@code true} even if a new content is received.
     */
    void onData(HttpData data);

    /**
     * Produces the preview.
     * Note that it is invoked when the request or response is ended.
     */
    String produce();

    /**
     * Determines if the previewer has been ready to produce the preview.
     */
    boolean isDone();
}

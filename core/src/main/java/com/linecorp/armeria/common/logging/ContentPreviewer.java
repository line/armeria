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

import java.nio.charset.Charset;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

import io.netty.buffer.ByteBuf;

/**
 * Produces the preview of {@link RequestLog}.
 */
public interface ContentPreviewer {

    /**
     * Creates a new instance of {@link ContentPreviewer} which produces the preview through {@code reproducer}
     * when the contents have been aggregated more than {@code length} bytes.
     */
    static ContentPreviewer ofBinary(int length, Function<? super ByteBuf, String> reproducer) {
        return ofBinary(length, (headers, buffer) -> reproducer.apply(buffer));
    }

    /**
     * Creates a new instance of {@link ContentPreviewer} which produces the preview through {@code reproducer}
     * when the contents have been aggregated more than {@code length} bytes.
     */
    static ContentPreviewer ofBinary(int length,
                                     BiFunction<? super HttpHeaders, ? super ByteBuf, String> reproducer) {
        if (length == 0) {
            return disabled();
        }
        return BinaryContentPreviewer.create(length, reproducer);
    }

    /**
     * Creates a new instance of {@link ContentPreviewer} which produces the text
     * with the maximum {@code length} limit.
     * @param length the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    static ContentPreviewer ofText(int length, Charset defaultCharset) {
        checkArgument(length >= 0, "length : %d (expected: >= 0)", length);
        if (length == 0) {
            return disabled();
        }
        return new StringContentPreviewer(length, defaultCharset);
    }

    /**
     * Creates a new instance of {@link ContentPreviewer} which produces the text
     * with the maximum {@code length} limit.
     * @param length the maximum length of the preview.
     */
    static ContentPreviewer ofText(int length) {
        return ofText(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    /**
     * A dummy {@link ContentPreviewer} which discards everything it collected and produces {@code null}.
     */
    static ContentPreviewer disabled() {
        return ContentPreviewerAdapter.NOOP;
    }

    /**
     * Returns whether this {@link ContentPreviewer} is {@link #disabled()} or not.
     */
    default boolean isDisabled() {
        return false;
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
     * Produces the preview of {@link RequestLog}.
     * Note that it is invoked when the request or response is ended
     * or the preview has been ready to be produced.
     * @return the preview, or {@code null} if disabled.
     */
    @Nullable
    String produce();

    /**
     * Determines if the previewer has been ready to produce the preview or
     * the preview has been already produced.
     */
    boolean isDone();
}

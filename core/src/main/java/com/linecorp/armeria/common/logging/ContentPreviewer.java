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

import java.nio.charset.Charset;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.buffer.ByteBuf;

/**
 * Produces the preview of {@link RequestLog}.
 */
public interface ContentPreviewer {

    /**
     * Returns a new {@link ContentPreviewer} which produces the text with the {@code maxLength} limit.
     *
     * <p>Note that {@link ContentPreviewerFactory#defaultCharset()} is used when a charset is not specified
     * in the {@code "content-type"} header.
     *
     * @param maxLength the maximum length of the preview.
     */
    static ContentPreviewer ofText(int maxLength) {
        return ofText(maxLength, ContentPreviewerFactory.defaultCharset());
    }

    /**
     * Returns a new {@link ContentPreviewer} which produces the text with the {@code maxLength} limit.
     *
     * @param maxLength the maximum length of the preview
     * @param charset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    static ContentPreviewer ofText(int maxLength, Charset charset) {
        checkArgument(maxLength > 0, "maxLength : %d (expected: > 0)", maxLength);
        return new StringContentPreviewer(maxLength, charset);
    }

    /**
     * Returns a new {@link ContentPreviewer} which produces the preview using {@link BiFunction}.
     */
    static ContentPreviewer ofBinary(int maxLength,
                                     BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer,
                                     HttpHeaders headers) {
        checkArgument(maxLength > 0, "maxLength : %d (expected: > 0)", maxLength);
        requireNonNull(producer, "producer");
        requireNonNull(headers, "headers");
        return new BinaryContentPreviewer(maxLength, producer, headers);
    }

    /**
     * A dummy {@link ContentPreviewer} which discards everything it collected and produces {@code null}.
     */
    static ContentPreviewer disabled() {
        return NoopContentPreviewer.NOOP;
    }

    /**
     * Returns whether this {@link ContentPreviewer} is {@link #disabled()} or not.
     */
    default boolean isDisabled() {
        return false;
    }

    /**
     * Invoked after request/response data is received.
     */
    void onData(HttpData data);

    /**
     * Produces the preview of the request or response.
     *
     * @return the preview, or {@code null} if disabled.
     */
    @Nullable
    String produce();
}

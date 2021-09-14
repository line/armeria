/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A complete body part whose headers and content are readily available.
 */
public interface AggregatedBodyPart extends AggregatedHttpObject {

    /**
     * Returns a new {@link AggregatedBodyPart}.
     */
    static AggregatedBodyPart of(HttpHeaders headers, HttpData content) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        return new DefaultAggregatedBodyPart(headers, content);
    }

    /**
     * Returns the control name.
     *
     * @return the {@code name} parameter of the {@code "content-disposition"}
     *         header, or {@code null} if not present.
     */
    @Nullable
    default String name() {
        final ContentDisposition contentDisposition = headers().contentDisposition();
        if (contentDisposition != null) {
            return contentDisposition.name();
        } else {
            return null;
        }
    }

    /**
     * Returns the file name.
     *
     * @return the {@code filename} parameter of the {@code "content-disposition"}
     *         header, or {@code null} if not present.
     */
    @Nullable
    default String filename() {
        final ContentDisposition contentDisposition = headers().contentDisposition();
        if (contentDisposition != null) {
            return contentDisposition.filename();
        } else {
            return null;
        }
    }
}

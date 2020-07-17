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
package com.linecorp.armeria.common.multipart;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpData;

/**
 * A complete body part whose headers and content are readily available.
 */
public final class AggregatedBodyPart {

    private final BodyPartHeaders headers;
    private final HttpData content;

    AggregatedBodyPart(BodyPartHeaders headers, HttpData content) {
        this.headers = headers;
        this.content = content;
    }

    /**
     * Returns HTTP part headers.
     */
    public BodyPartHeaders headers() {
        return headers;
    }

    /**
     * Returns the content of the {@link AggregatedBodyPart}.
     */
    public HttpData content() {
        return content;
    }

    /**
     * Returns the content string of the {@link AggregatedBodyPart} using UTF-8 encoding.
     */
    public String contentUtf8() {
        return content.toStringUtf8();
    }

    /**
     * Returns the content string of the {@link AggregatedBodyPart} using US-ASCII encoding.
     */
    public String contentAscii() {
        return content.toStringAscii();
    }

    /**
     * Retuns the control name.
     *
     * @return the {@code name} parameter of the {@code Content-Disposition}
     *         header, or {@code null} if not present.
     */
    @Nullable
    public String name() {
        return headers().contentDisposition().name();
    }

    /**
     * Return the file name.
     *
     * @return the {@code filename} parameter of the {@code Content-Disposition}
     *         header, or {@code null} if not present.
     */
    @Nullable
    public String filename() {
        return headers().contentDisposition().filename();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name())
                          .add("filename", filename())
                          .add("headers", headers)
                          .add("content", content)
                          .toString();
    }
}

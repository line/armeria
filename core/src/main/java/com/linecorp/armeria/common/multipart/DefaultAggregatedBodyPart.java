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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

/**
 * A complete body part whose headers and content are readily available.
 */
final class DefaultAggregatedBodyPart implements AggregatedBodyPart {

    private final HttpHeaders headers;
    private final HttpData content;

    DefaultAggregatedBodyPart(HttpHeaders headers, HttpData content) {
        this.headers = headers;
        this.content = content;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public HttpData content() {
        return content;
    }

    @Override
    public String contentUtf8() {
        return content.toStringUtf8();
    }

    @Override
    public String contentAscii() {
        return content.toStringAscii();
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

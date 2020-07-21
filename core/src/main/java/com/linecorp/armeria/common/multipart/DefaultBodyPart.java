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

import org.reactivestreams.Publisher;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

final class DefaultBodyPart implements BodyPart {

    private final HttpHeaders headers;
    private final Publisher<? extends HttpData> content;

    DefaultBodyPart(HttpHeaders headers, Publisher<? extends HttpData> content) {
        this.headers = headers;
        this.content = content;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Publisher<HttpData> content() {
        return (Publisher<HttpData>) content;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("headers", headers)
                          .add("content", content)
                          .toString();
    }
}

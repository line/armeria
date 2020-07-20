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

final class DefaultBodyPart implements BodyPart {

    private final Publisher<? extends HttpData> content;
    private final BodyPartHeaders headers;

    DefaultBodyPart(Publisher<? extends HttpData> content, BodyPartHeaders headers) {
        this.content = content;
        this.headers = headers;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Publisher<HttpData> content() {
        return (Publisher<HttpData>) content;
    }

    @Override
    public BodyPartHeaders headers() {
        return headers;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("headers", headers)
                          .add("content", content)
                          .toString();
    }
}

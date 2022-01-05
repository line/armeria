/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultRequestEntity<T> implements RequestEntity<T> {

    private final RequestHeaders headers;
    @Nullable
    private final T content;
    private final HttpHeaders trailers;

    DefaultRequestEntity(RequestHeaders headers, @Nullable T content, HttpHeaders trailers) {
        this.headers = headers;
        this.content = content;
        this.trailers = trailers;
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @Override
    public T content() {
        if (content == null) {
            throw new NoContentException("content is empty.");
        }
        return content;
    }

    @Override
    public boolean hasContent() {
        return content != null;
    }

    @Override
    public HttpHeaders trailers() {
        return trailers;
    }
}

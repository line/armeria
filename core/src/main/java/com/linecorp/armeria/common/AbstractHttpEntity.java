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

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;

abstract class AbstractHttpEntity<T> implements HttpEntity<T> {

    private final HttpHeaders headers;
    @Nullable
    private final T content;
    private final HttpHeaders trailers;

    AbstractHttpEntity(HttpHeaders headers, @Nullable T content, HttpHeaders trailers) {
        this.headers = headers;
        this.content = content;
        this.trailers = trailers;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public T content() {
        if (content == null) {
            throw new NoHttpContentException("No content present.");
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AbstractHttpEntity)) {
            return false;
        }

        final AbstractHttpEntity<?> that = (AbstractHttpEntity<?>) o;
        return headers.equals(that.headers) &&
               Objects.equal(content, that.content) &&
               trailers.equals(that.trailers);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(headers, content, trailers);
    }

    @Override
    public String toString() {
        final ToStringHelper stringHelper =
                MoreObjects.toStringHelper(this)
                           .omitNullValues()
                           .add("headers", headers)
                           .add("content", content);

        if (!trailers.isEmpty()) {
            stringHelper.add("trailers", trailers);
        }
        return stringHelper.toString();
    }
}

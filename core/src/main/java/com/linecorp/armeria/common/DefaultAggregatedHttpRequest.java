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

package com.linecorp.armeria.common;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultAggregatedHttpRequest extends AbstractAggregatedHttpMessage
        implements AggregatedHttpRequest {

    private final RequestHeaders headers;

    DefaultAggregatedHttpRequest(RequestHeaders headers, HttpData content,
                                 HttpHeaders trailers) {
        super(content, trailers);
        this.headers = headers;
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @Override
    public HttpMethod method() {
        return headers.method();
    }

    @Override
    public String path() {
        return headers.path();
    }

    @Override
    public String scheme() {
        return headers.scheme();
    }

    @Override
    public String authority() {
        return headers.authority();
    }

    @Override
    public int hashCode() {
        int result = headers().hashCode();
        result = 31 * result + content().hashCode();
        result = 31 * result + trailers().hashCode();
        return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof AggregatedHttpRequest)) {
            return false;
        }

        final AggregatedHttpRequest that = (AggregatedHttpRequest) obj;

        return headers().equals(that.headers()) &&
               content().equals(that.content()) &&
               trailers().equals(that.trailers());
    }

    @Override
    public String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this);

        helper.add("headers", headers())
              .add("content", content());

        if (!trailers().isEmpty()) {
            helper.add("trailers", trailers());
        }

        return helper.toString();
    }
}

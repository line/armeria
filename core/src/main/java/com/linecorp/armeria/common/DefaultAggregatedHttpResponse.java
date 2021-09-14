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

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultAggregatedHttpResponse extends AbstractAggregatedHttpMessage
        implements AggregatedHttpResponse {

    private final List<ResponseHeaders> informationals;
    private final ResponseHeaders headers;

    DefaultAggregatedHttpResponse(List<ResponseHeaders> informationals, ResponseHeaders headers,
                                 HttpData content, HttpHeaders trailers) {
        super(content, trailers);
        this.informationals = informationals;
        this.headers = headers;
    }

    @Override
    public List<ResponseHeaders> informationals() {
        return informationals;
    }

    @Override
    public HttpStatus status() {
        return headers.status();
    }

    @Override
    public ResponseHeaders headers() {
        return headers;
    }

    @Override
    public int hashCode() {
        int result = informationals().hashCode();
        result = 31 * result + headers().hashCode();
        result = 31 * result + content().hashCode();
        result = 31 * result + trailers().hashCode();
        return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof AggregatedHttpResponse)) {
            return false;
        }

        final AggregatedHttpResponse that = (AggregatedHttpResponse) obj;

        return informationals().equals(that.informationals()) &&
               headers().equals(that.headers()) &&
               content().equals(that.content()) &&
               trailers().equals(that.trailers());
    }

    @Override
    public String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this);

        if (!informationals().isEmpty()) {
            helper.add("informationals", informationals());
        }

        helper.add("headers", headers())
              .add("content", content());

        if (!trailers().isEmpty()) {
            helper.add("trailers", trailers());
        }

        return helper.toString();
    }
}

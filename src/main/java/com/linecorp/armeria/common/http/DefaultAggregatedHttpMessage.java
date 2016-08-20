/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

final class DefaultAggregatedHttpMessage implements AggregatedHttpMessage {

    private final List<HttpHeaders> informationals;
    private final HttpHeaders headers;
    private final HttpData content;
    private final HttpHeaders trailingHeaders;

    DefaultAggregatedHttpMessage(List<HttpHeaders> informationals, HttpHeaders headers,
                                 HttpData content, HttpHeaders trailingHeaders) {
        this.informationals = informationals;
        this.headers = headers;
        this.content = content;
        this.trailingHeaders = trailingHeaders;
    }

    @Override
    public List<HttpHeaders> informationals() {
        return informationals;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public HttpHeaders trailingHeaders() {
        return trailingHeaders;
    }

    @Override
    public HttpData content() {
        return content;
    }

    @Override
    public String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this);

        if (!informationals().isEmpty()) {
            helper.add("informationals", informationals());
        }

        helper.add("headers", headers())
              .add("content", content());

        if (!trailingHeaders().isEmpty()) {
            helper.add("trailingHandlers", trailingHeaders());
        }

        return helper.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof AggregatedHttpMessage)) {
            return false;
        }

        final DefaultAggregatedHttpMessage that = (DefaultAggregatedHttpMessage) obj;

        return informationals.equals(that.informationals)
                && headers.equals(that.headers)
                && content.equals(that.content)
                && trailingHeaders.equals(that.trailingHeaders);
    }

    @Override
    public int hashCode() {
        int result = informationals.hashCode();
        result = 31 * result + headers.hashCode();
        result = 31 * result + content.hashCode();
        result = 31 * result + trailingHeaders.hashCode();
        return result;
    }
}

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

package com.linecorp.armeria.client.http;

import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpResponse;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * A container for information returned in an HTTP response. This is a simpler version of
 * {@link FullHttpResponse} which only uses a byte array to avoid callers having to worry about memory
 * management.
 *
 * @deprecated Use {@link AggregatedHttpMessage} instead.
 */
@Deprecated
public class SimpleHttpResponse {

    private final HttpResponseStatus status;
    private final HttpHeaders headers;
    private final byte[] content;

    SimpleHttpResponse(HttpResponseStatus status, HttpHeaders headers, byte[] content) {
        this.status = status;
        this.headers = headers;
        this.content = content;
    }

    /**
     * Returns the HTTP status.
     */
    public HttpResponseStatus status() {
        return status;
    }

    /**
     * Returns the HTTP response headers.
     */
    public HttpHeaders headers() {
        return headers;
    }

    /**
     * Returns the HTTP response content.
     *
     * @return the HTTP response content, or an empty array if there's no content
     */
    public byte[] content() {
        return content;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append('(');
        buf.append("status: ").append(status);
        buf.append(", headers: ").append(headers);
        buf.append(", content: ");
        if (content.length > 0) {
            buf.append("<length: ").append(content.length).append('>');
        } else {
            buf.append("<none>");
        }
        buf.append(')');
        return buf.toString();
    }
}

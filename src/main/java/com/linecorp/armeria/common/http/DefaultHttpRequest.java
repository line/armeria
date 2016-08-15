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

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.stream.DefaultStreamMessage;

/**
 * Default {@link HttpRequest} implementation.
 */
public class DefaultHttpRequest
        extends DefaultStreamMessage<HttpObject> implements HttpRequest, HttpRequestWriter {

    private final HttpHeaders headers;
    private final boolean keepAlive;

    /**
     * Creates a new instance with empty headers.
     */
    public DefaultHttpRequest() {
        this(new DefaultHttpHeaders());
    }

    /**
     * Creates a new instance with the specified headers.
     */
    public DefaultHttpRequest(HttpHeaders headers) {
        this(headers, true);
    }

    /**
     * Creates a new instance with the specified {@link HttpMethod} and path.
     */
    public DefaultHttpRequest(HttpMethod method, String path) {
        this(HttpHeaders.of(method, path));
    }

    /**
     * Creates a new instance with the specified headers.
     *
     * @param keepAlive whether to keep the connection alive after this request is handled
     */
    public DefaultHttpRequest(HttpHeaders headers, boolean keepAlive) {
        this.headers = requireNonNull(headers, "headers");
        this.keepAlive = keepAlive;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public boolean isKeepAlive() {
        return keepAlive;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("keepAlive", isKeepAlive())
                          .add("headers", headers()).toString();
    }
}

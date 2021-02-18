/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestOptions;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;

/**
 * Default {@link HttpRequest} implementation.
 */
public class DefaultHttpRequest extends DefaultStreamMessage<HttpObject> implements HttpRequestWriter {

    private final RequestHeaders headers;
    private final RequestOptions options;

    /**
     * Creates a new instance with the specified headers.
     */
    public DefaultHttpRequest(RequestHeaders headers) {
        this(headers, RequestOptions.of());
    }

    /**
     * Creates a new instance with the specified headers and options.
     */
    public DefaultHttpRequest(RequestHeaders headers, RequestOptions options) {
        this.headers = requireNonNull(headers, "headers");
        this.options = requireNonNull(options, "options");
    }

    @Override
    public final RequestHeaders headers() {
        return headers;
    }

    @Override
    public RequestOptions options() {
        return options;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .addValue(headers())
                          .addValue(options())
                          .toString();
    }
}

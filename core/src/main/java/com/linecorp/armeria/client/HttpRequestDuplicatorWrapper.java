/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.client;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.RequestHeaders;

final class HttpRequestDuplicatorWrapper implements HttpRequestDuplicator {

    private final HttpRequestDuplicator delegate;
    private final RequestHeaders headers;

    HttpRequestDuplicatorWrapper(HttpRequestDuplicator delegate, RequestHeaders headers) {
        if (delegate instanceof HttpRequestDuplicatorWrapper) {
            this.delegate = ((HttpRequestDuplicatorWrapper) delegate).delegate();
        } else {
            this.delegate = delegate;
        }
        this.headers = headers;
    }

    HttpRequestDuplicator delegate() {
        return delegate;
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @Override
    public HttpRequest duplicate() {
        return delegate.duplicate(headers);
    }

    @Override
    public HttpRequest duplicate(RequestHeaders newHeaders) {
        return delegate.duplicate(newHeaders);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(cause);
    }
}

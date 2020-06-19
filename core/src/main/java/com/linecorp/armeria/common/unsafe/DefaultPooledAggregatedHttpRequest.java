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

package com.linecorp.armeria.common.unsafe;

import java.nio.charset.Charset;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;

final class DefaultPooledAggregatedHttpRequest implements PooledAggregatedHttpRequest {

    private final AggregatedHttpRequest delegate;
    private final PooledHttpData content;

    DefaultPooledAggregatedHttpRequest(AggregatedHttpRequest delegate) {
        this.delegate = delegate;

        content = PooledHttpData.of(delegate.content());
    }

    @Override
    public RequestHeaders headers() {
        return delegate.headers();
    }

    @Override
    public HttpMethod method() {
        return delegate.method();
    }

    @Override
    public String path() {
        return delegate.path();
    }

    @Override
    @Nullable
    public String scheme() {
        return delegate.scheme();
    }

    @Override
    @Nullable
    public String authority() {
        return delegate.authority();
    }

    @Override
    public HttpHeaders trailers() {
        return delegate.trailers();
    }

    @Override
    @Nullable
    public MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public PooledHttpData content() {
        return content;
    }

    @Override
    public String content(Charset charset) {
        return content.toString(charset);
    }

    @Override
    public String contentUtf8() {
        return content.toStringUtf8();
    }

    @Override
    public String contentAscii() {
        return content.toStringAscii();
    }

    @Override
    public PooledHttpRequest toHttpRequest() {
        return PooledHttpRequest.of(delegate.toHttpRequest());
    }

    @Override
    public void close() {
        content.close();
    }
}

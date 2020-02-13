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

package com.linecorp.armeria.unsafe;

import java.nio.charset.Charset;
import java.util.List;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;

final class DefaultPooledAggregatedHttpResponse implements PooledAggregatedHttpResponse {

    private final AggregatedHttpResponse delegate;
    private final PooledHttpData content;

    DefaultPooledAggregatedHttpResponse(AggregatedHttpResponse delegate) {
        this.delegate = delegate;
        content = ByteBufHttpData.convert(delegate.content());
    }

    @Override
    public ResponseHeaders headers() {
        return delegate.headers();
    }

    @Override
    public List<ResponseHeaders> informationals() {
        return delegate.informationals();
    }

    @Override
    public HttpStatus status() {
        return delegate.status();
    }

    @Override
    public HttpHeaders trailers() {
        return delegate.trailers();
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
    @Nullable
    public MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public void close() {
        content.close();
    }
}

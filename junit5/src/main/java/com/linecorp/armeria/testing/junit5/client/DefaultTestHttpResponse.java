/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.testing.junit5.client;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultTestHttpResponse implements TestHttpResponse {

    private final AggregatedHttpResponse delegate;

    DefaultTestHttpResponse(AggregatedHttpResponse delegate) {
        requireNonNull(delegate, "delegate");
        this.delegate = delegate;
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
    public ResponseHeaders headers() {
        return delegate.headers();
    }

    @Override
    public HttpData content() {
        return delegate.content();
    }

    @Override
    public HttpHeaders trailers() {
        return delegate.trailers();
    }

    @Override
    public AggregatedHttpResponse unwrap() {
        return delegate;
    }

    @Override
    public HttpStatusAssert assertStatus() {
        return new HttpStatusAssert(status(), this);
    }

    @Override
    public HttpHeadersAssert assertHeaders() {
        return new HttpHeadersAssert(headers(), this);
    }

    @Override
    public HttpDataAssert assertContent() {
        return new HttpDataAssert(content(), this);
    }

    @Override
    public HttpHeadersAssert assertTrailers() {
        return new HttpHeadersAssert(trailers(), this);
    }

    @Override
    public ThrowableAssert assertCause() {
        throw new AssertionError("Expecting the response to raise a throwable.");
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof TestHttpResponse)) {
            return false;
        }

        final TestHttpResponse that = (TestHttpResponse) obj;

        return delegate.equals(that.unwrap());
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}

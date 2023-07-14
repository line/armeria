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

final class AbortedTestHttpResponse implements TestHttpResponse {

    private final Throwable cause;

    AbortedTestHttpResponse(Throwable cause) {
        requireNonNull(cause, "cause");
        this.cause = cause;
    }

    @Override
    public List<ResponseHeaders> informationals() {
        throw new RuntimeException(cause);
    }

    @Override
    public HttpHeaders trailers() {
        throw new RuntimeException(cause);
    }

    @Override
    public HttpData content() {
        throw new RuntimeException(cause);
    }

    @Override
    public ResponseHeaders headers() {
        throw new RuntimeException(cause);
    }

    @Override
    public HttpStatus status() {
        throw new RuntimeException(cause);
    }

    @Override
    public AggregatedHttpResponse unwrap() {
        throw new RuntimeException(cause);
    }

    @Override
    public HttpStatusAssert assertStatus() {
        throw new RuntimeException(cause);
    }

    @Override
    public HttpHeadersAssert assertHeaders() {
        throw new RuntimeException(cause);
    }

    @Override
    public HttpDataAssert assertContent() {
        throw new RuntimeException(cause);
    }

    @Override
    public HttpHeadersAssert assertTrailers() {
        throw new RuntimeException(cause);
    }

    @Override
    public ThrowableAssert assertCause() {
        return new ThrowableAssert(cause);
    }
}

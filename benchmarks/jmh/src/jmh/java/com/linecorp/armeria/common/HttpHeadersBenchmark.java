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

package com.linecorp.armeria.common;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Microbenchmarks of {@link DefaultHttpHeaders} construction.
 */
public class HttpHeadersBenchmark {

    private static final String AUTHORIZATION_TOKEN =
            // Sample JWT from https://jwt.io. Most auth tokens will be significantly longer.
            "bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ." +
            "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    @Nullable
    @Benchmark
    public MediaType parseKnown() {
        final HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto");
        return headers.contentType();
    }

    @Nullable
    @Benchmark
    public MediaType parseUnknown() {
        final HttpHeaders headers = HttpHeaders.of(
                // Single letter change to keep theoretical parsing performance the same.
                HttpHeaderNames.CONTENT_TYPE, "application/grpc+oroto");
        return headers.contentType();
    }

    @Benchmark
    public HttpHeaders create_validation() {
        return HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, AUTHORIZATION_TOKEN);
    }

    @Benchmark
    @Fork(jvmArgsAppend = "-Dcom.linecorp.armeria.validateHeaders=false")
    public HttpHeaders create_noValidation() {
        return HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, AUTHORIZATION_TOKEN);
    }
}

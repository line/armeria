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

/**
 * Microbenchmarks of {@link DefaultHttpHeaders} construction.
 */
public class HttpHeadersBenchmark {

    @Benchmark
    public MediaType parseKnown() {
        final HttpHeaders headers = new DefaultHttpHeaders()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto");
        return headers.contentType();
    }

    @Benchmark
    public MediaType parseUnknown() {
        final HttpHeaders headers = new DefaultHttpHeaders()
                // Single letter change to keep theoretical parsing performance the same.
                .set(HttpHeaderNames.CONTENT_TYPE, "application/grpc+oroto");
        return headers.contentType();
    }
}

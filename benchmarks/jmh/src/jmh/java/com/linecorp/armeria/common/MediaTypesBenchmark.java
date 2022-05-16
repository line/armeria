/*
 * Copyright 2018 LINE Corporation
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
import org.openjdk.jmh.infra.Blackhole;

/**
 * Microbenchmarks for matching media types.
 */
public class MediaTypesBenchmark {

    private static final MediaTypeSet MEDIA_TYPES = MediaTypeSet.of(
            MediaType.create("application", "grpc"), MediaType.create("application", "grpc+proto"));

    private static final MediaType GRPC_MEDIA_TYPE = MediaType.create("application", "grpc");

    private static final MediaType NOT_GRPC_MEDIA_TYPE = MediaType.create("application", "json");

    private static final MediaType GRPC_MEDIA_TYPE_WITH_PARAMS =
            MediaType.parse("application/grpc; charset=utf-8; q=0.9");

    private static final MediaType NOT_GRPC_MEDIA_TYPE_WITH_PARAMS =
            MediaType.parse("application/json; charset=utf-8; q=0.9");

    @Benchmark
    public void simpleMatch(Blackhole bh) {
        bh.consume(MEDIA_TYPES.match(GRPC_MEDIA_TYPE));
    }

    @Benchmark
    public void simpleNotMatch(Blackhole bh) {
        bh.consume(MEDIA_TYPES.match(NOT_GRPC_MEDIA_TYPE));
    }

    @Benchmark
    public void complexMatch(Blackhole bh) {
        bh.consume(MEDIA_TYPES.match(GRPC_MEDIA_TYPE_WITH_PARAMS));
    }

    @Benchmark
    public void complexNotMatch(Blackhole bh) {
        bh.consume(MEDIA_TYPES.match(NOT_GRPC_MEDIA_TYPE_WITH_PARAMS));
    }

    @Benchmark
    public void all(Blackhole bh) {
        // Do all matches to reduce branch predictor accuracy.
        bh.consume(MEDIA_TYPES.match(GRPC_MEDIA_TYPE));
        bh.consume(MEDIA_TYPES.match(NOT_GRPC_MEDIA_TYPE));
        bh.consume(MEDIA_TYPES.match(GRPC_MEDIA_TYPE_WITH_PARAMS));
        bh.consume(MEDIA_TYPES.match(NOT_GRPC_MEDIA_TYPE_WITH_PARAMS));
    }
}

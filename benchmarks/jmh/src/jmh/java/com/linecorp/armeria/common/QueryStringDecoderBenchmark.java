/*
 * Copyright 2019 LINE Corporation
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

import java.util.List;
import java.util.Map;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * Microbenchmarks for encoding query parameters.
 */
public class QueryStringDecoderBenchmark {

    private static final String ASCII_PARAMS = QueryStringEncoderBenchmark.ASCII_PARAMS.toQueryString();
    private static final String UNICODE_PARAMS = QueryStringEncoderBenchmark.UNICODE_PARAMS.toQueryString();
    private static final String MIXED_PARAMS = QueryStringEncoderBenchmark.MIXED_PARAMS.toQueryString();
    private static final String LONG_PARAMS = QueryStringEncoderBenchmark.LONG_PARAMS.toQueryString();

    @Benchmark
    public void armeriaAscii(Blackhole bh) {
        bh.consume(QueryParams.fromQueryString(ASCII_PARAMS));
    }

    @Benchmark
    public void armeriaUnicode(Blackhole bh) {
        bh.consume(QueryParams.fromQueryString(UNICODE_PARAMS));
    }

    @Benchmark
    public void armeriaMixed(Blackhole bh) {
        bh.consume(QueryParams.fromQueryString(MIXED_PARAMS));
    }

    @Benchmark
    public void armeriaLong(Blackhole bh) {
        bh.consume(QueryParams.fromQueryString(LONG_PARAMS));
    }

    @Benchmark
    public void nettyAscii(Blackhole bh) {
        bh.consume(nettyDecode(ASCII_PARAMS));
    }

    @Benchmark
    public void nettyUnicode(Blackhole bh) {
        bh.consume(nettyDecode(UNICODE_PARAMS));
    }

    @Benchmark
    public void nettyMixed(Blackhole bh) {
        bh.consume(nettyDecode(MIXED_PARAMS));
    }

    @Benchmark
    public void nettyLong(Blackhole bh) {
        bh.consume(nettyDecode(LONG_PARAMS));
    }

    private static Map<String, List<String>> nettyDecode(String queryString) {
        return new QueryStringDecoder(queryString, false).parameters();
    }
}

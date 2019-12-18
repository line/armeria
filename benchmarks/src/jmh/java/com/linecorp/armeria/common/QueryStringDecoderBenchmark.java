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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;

import com.linecorp.armeria.internal.TemporaryThreadLocals;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * Microbenchmarks for encoding query parameters.
 */
public class QueryStringDecoderBenchmark {

    private static final Escaper guavaEscaper = UrlEscapers.urlFormParameterEscaper();

    private static final String ASCII_PARAMS;
    private static final String UNICODE_PARAMS;
    private static final String MIXED_PARAMS;

    static {
        final QueryParamsBuilder ascii = QueryParams.builder();
        for (int i = 0; i < 10; i++) {
            ascii.add("alpha", "beta_gamma")
                 .add("delta", "epsilon_zeta")
                 .add("eta", "theta_iota")
                 .add("kappa", "lambda_mu");
        }
        ASCII_PARAMS = ascii.build().toQueryString();

        final QueryParamsBuilder unicode = QueryParams.builder();
        for (int i = 0; i < 10; i++) {
            unicode.add("알파", "베타・감마") // Hangul
                   .add("アルファ", "ベータ・ガンマ") // Katakana
                   .add("电买车红", "无东马风") // Simplified Chinese
                   .add("🎄❤️😂", "🎅🔥😊🎁"); // Emoji
        }
        UNICODE_PARAMS = unicode.build().toQueryString();

        final QueryParamsBuilder mixed = QueryParams.builder();
        for (int i = 0; i < 10; i++) {
            mixed.add("foo", "alpha・ベータ")
                 .add("bar", "ガンマ・delta")
                 .add("baz", "nothing_无_east_东_horse_马_wind_风")
                 .add("qux", "santa_🎅_fire_🔥_smile_😊_present_🎁");
        }
        MIXED_PARAMS = mixed.build().toQueryString();
    }

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

    private static Map<String, List<String>> nettyDecode(String queryString) {
        return new QueryStringDecoder(queryString, false).parameters();
    }
}

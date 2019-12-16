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
import java.nio.charset.StandardCharsets;
import java.util.Map.Entry;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;

import com.linecorp.armeria.internal.TemporaryThreadLocals;

import io.netty.handler.codec.http.QueryStringEncoder;

/**
 * Microbenchmarks for encoding query parameters.
 */
public class QueryStringEncoderBenchmark {

    private static final Escaper guavaEscaper = UrlEscapers.urlFormParameterEscaper();

    private static final QueryParams ASCII_PARAMS;
    private static final QueryParams UNICODE_PARAMS;
    private static final QueryParams MIXED_PARAMS;

    static {
        final QueryParamsBuilder ascii = QueryParams.builder();
        for (int i = 0; i < 10; i++) {
            ascii.add("alpha", "beta_gamma")
                 .add("delta", "epsilon_zeta")
                 .add("eta", "theta_iota")
                 .add("kappa", "lambda_mu");
        }
        ASCII_PARAMS = ascii.build();

        final QueryParamsBuilder unicode = QueryParams.builder();
        for (int i = 0; i < 10; i++) {
            unicode.add("알파", "베타・감마") // Hangul
                   .add("アルファ", "ベータ・ガンマ") // Katakana
                   .add("电买车红", "无东马风") // Simplified Chinese
                   .add("🎄❤️😂", "🎅🔥😊🎁"); // Emoji
        }
        UNICODE_PARAMS = unicode.build();

        final QueryParamsBuilder mixed = QueryParams.builder();
        for (int i = 0; i < 10; i++) {
            mixed.add("foo", "alpha・ベータ")
                 .add("bar", "ガンマ・delta")
                 .add("baz", "nothing_无_east_东_horse_马_wind_风")
                 .add("qux", "santa_🎅_fire_🔥_smile_😊_present_🎁");
        }
        MIXED_PARAMS = mixed.build();
    }

    @Benchmark
    public void armeriaAscii(Blackhole bh) {
        bh.consume(ASCII_PARAMS.toQueryString());
    }

    @Benchmark
    public void armeriaUnicode(Blackhole bh) {
        bh.consume(UNICODE_PARAMS.toQueryString());
    }

    @Benchmark
    public void armeriaMixed(Blackhole bh) {
        bh.consume(MIXED_PARAMS.toQueryString());
    }

    @Benchmark
    public void guavaAscii(Blackhole bh) {
        bh.consume(guavaEncode(ASCII_PARAMS));
    }

    @Benchmark
    public void guavaUnicode(Blackhole bh) {
        bh.consume(guavaEncode(UNICODE_PARAMS));
    }

    @Benchmark
    public void guavaMixed(Blackhole bh) {
        bh.consume(guavaEncode(MIXED_PARAMS));
    }

    private static String guavaEncode(QueryParamGetters params) {
        final StringBuilder buf = TemporaryThreadLocals.get().stringBuilder();
        for (Entry<String, String> e : params) {
            buf.append(guavaEscaper.escape(e.getKey()))
               .append('=')
               .append(guavaEscaper.escape(e.getValue()))
               .append('&');
        }
        return buf.substring(0, buf.length() - 1);
    }

    @Benchmark
    public void nettyAscii(Blackhole bh) {
        bh.consume(nettyEncode(ASCII_PARAMS));
    }

    @Benchmark
    public void nettyUnicode(Blackhole bh) {
        bh.consume(nettyEncode(UNICODE_PARAMS));
    }

    @Benchmark
    public void nettyMixed(Blackhole bh) {
        bh.consume(nettyEncode(MIXED_PARAMS));
    }

    private static String nettyEncode(QueryParamGetters params) {
        final QueryStringEncoder encoder = new QueryStringEncoder("", StandardCharsets.UTF_8);
        for (Entry<String, String> e : params) {
            encoder.addParam(e.getKey(), e.getValue());
        }
        return encoder.toString();
    }

    @Benchmark
    public void jdkAscii(Blackhole bh) throws UnsupportedEncodingException {
        bh.consume(jdkEncode(ASCII_PARAMS));
    }

    @Benchmark
    public void jdkUnicode(Blackhole bh) throws UnsupportedEncodingException {
        bh.consume(jdkEncode(UNICODE_PARAMS));
    }

    @Benchmark
    public void jdkMixed(Blackhole bh) throws UnsupportedEncodingException {
        bh.consume(jdkEncode(MIXED_PARAMS));
    }

    private static String jdkEncode(QueryParamGetters params) throws UnsupportedEncodingException {
        final StringBuilder buf = TemporaryThreadLocals.get().stringBuilder();
        for (Entry<String, String> e : params) {
            buf.append(URLEncoder.encode(e.getKey(), "UTF-8"))
               .append('=')
               .append(URLEncoder.encode(e.getValue(), "UTF-8"))
               .append('&');
        }
        return buf.substring(0, buf.length() - 1);
    }
}

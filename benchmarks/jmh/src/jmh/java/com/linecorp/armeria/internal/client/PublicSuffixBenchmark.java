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

package com.linecorp.armeria.internal.client;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.google.common.net.InternetDomainName;

/**
 * Microbenchmarks of {@link PublicSuffix}.
 */
@State(Scope.Benchmark)
public class PublicSuffixBenchmark {

    private final PublicSuffix publicSuffix = PublicSuffix.get();

    @Param({"com", "a.com", "biz", "biz.vn", "a.biz.vn", "uk.com", "com.ac", "test.ac", "mm", "c.mm", "b.c.mm",
            "a.b.c.mm", "jp", "a.jp", "kyoto.jp", "a.kyoto.jp", "kawasaki.jp", "a.kawasaki.jp",
            "city.kawasaki.jp", "ck", "a.ck", "a.b.ck", "www.ck", "compute.amazonaws.com",
            "b.compute.amazonaws.com", "b.compute.amazonaws.com.cn", "a.b.compute.amazonaws.com",
            "dev.adobeaemcloud.com", "a.dev.adobeaemcloud.com", "xn--12c1fe0br.xn--o3cw4h",
            "xn--12c1fe0br.xn--12c1fe0br.xn--o3cw4h", "xn--mgbi4ecexp",})
    private String domain;

    @Benchmark
    @Warmup(iterations = 1)
    @Measurement(iterations = 1)
    public void armeriaPublicSuffix(Blackhole bh) {
        bh.consume(publicSuffix.isPublicSuffix(domain));
    }

    @Benchmark
    @Warmup(iterations = 1)
    @Measurement(iterations = 1)
    public void guavaPublicSuffix(Blackhole bh) {
        bh.consume(InternetDomainName.from(domain).isPublicSuffix());
    }
}

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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Microbenchmarks for the verbose exception samplers.
 */
public class ExceptionSamplerBenchmark {

    private static final ExceptionSampler never = new ExceptionSampler("never");
    private static final ExceptionSampler always = new ExceptionSampler("always");
    private static final ExceptionSampler rateLimited1 = new ExceptionSampler("rate-limited=1");
    private static final ExceptionSampler rateLimited10 = new ExceptionSampler("rate-limited=10");
    private static final ExceptionSampler random1 = new ExceptionSampler("random=0.01");
    private static final ExceptionSampler random10 = new ExceptionSampler("random=0.1");

    @Benchmark
    public void baseline_singleton(Blackhole bh) {
        bh.consume(ExceptionA.INSTANCE);
        bh.consume(ExceptionB.INSTANCE);
    }

    @Benchmark
    public void baseline_withoutStackTrace(Blackhole bh) {
        bh.consume(new ExceptionA(false));
        bh.consume(new ExceptionB(false));
    }

    @Benchmark
    public void baseline_withStackTrace(Blackhole bh) {
        bh.consume(new ExceptionA());
        bh.consume(new ExceptionB());
    }

    @Benchmark
    public void sampler_never(Blackhole bh) {
        bh.consume(never.isSampled(ExceptionA.class) ? new ExceptionA() : ExceptionA.INSTANCE);
        bh.consume(never.isSampled(ExceptionB.class) ? new ExceptionB() : ExceptionB.INSTANCE);
    }

    @Benchmark
    public void sampler_rateLimited_1(Blackhole bh) {
        bh.consume(rateLimited1.isSampled(ExceptionA.class) ? new ExceptionA() : ExceptionA.INSTANCE);
        bh.consume(rateLimited1.isSampled(ExceptionB.class) ? new ExceptionB() : ExceptionB.INSTANCE);
    }

    @Benchmark
    public void sampler_rateLimited_10(Blackhole bh) {
        bh.consume(rateLimited10.isSampled(ExceptionA.class) ? new ExceptionA() : ExceptionA.INSTANCE);
        bh.consume(rateLimited10.isSampled(ExceptionB.class) ? new ExceptionB() : ExceptionB.INSTANCE);
    }

    @Benchmark
    public void sampler_random_1(Blackhole bh) {
        bh.consume(random1.isSampled(ExceptionA.class) ? new ExceptionA() : ExceptionA.INSTANCE);
        bh.consume(random1.isSampled(ExceptionB.class) ? new ExceptionB() : ExceptionB.INSTANCE);
    }

    @Benchmark
    public void sampler_random_10(Blackhole bh) {
        bh.consume(random10.isSampled(ExceptionA.class) ? new ExceptionA() : ExceptionA.INSTANCE);
        bh.consume(random10.isSampled(ExceptionB.class) ? new ExceptionB() : ExceptionB.INSTANCE);
    }

    @Benchmark
    public void sampler_always(Blackhole bh) {
        bh.consume(always.isSampled(ExceptionA.class) ? new ExceptionA() : ExceptionA.INSTANCE);
        bh.consume(always.isSampled(ExceptionB.class) ? new ExceptionB() : ExceptionB.INSTANCE);
    }

    private static final class ExceptionA extends Throwable {
        private static final long serialVersionUID = 7024319028414180839L;

        static final ExceptionA INSTANCE = new ExceptionA(false);

        ExceptionA() {}

        private ExceptionA(@SuppressWarnings("unused") boolean dummy) {
            super(null, null, false, false);
        }
    }

    private static final class ExceptionB extends Throwable {
        private static final long serialVersionUID = 1617065108978544599L;

        static final ExceptionB INSTANCE = new ExceptionB(false);

        ExceptionB() {}

        private ExceptionB(@SuppressWarnings("unused") boolean dummy) {
            super(null, null, false, false);
        }
    }
}

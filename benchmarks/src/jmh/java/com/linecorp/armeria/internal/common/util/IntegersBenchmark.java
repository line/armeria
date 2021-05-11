/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.internal.common.util;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

public class IntegersBenchmark {

    @Benchmark
    public void pooledIntToString(Blackhole bh) {
        for (int i = 0; i < 1000; i++) {
            bh.consume(Integers.toString(i));
        }
    }

    @Benchmark
    public void jdkIntToString(Blackhole bh) {
        for (int i = 0; i < 1000; i++) {
            bh.consume(Integer.toString(i));
        }
    }
}

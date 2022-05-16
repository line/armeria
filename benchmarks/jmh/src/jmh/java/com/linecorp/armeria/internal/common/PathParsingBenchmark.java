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

package com.linecorp.armeria.internal.common;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Microbenchmarks for the {@link PathAndQuery#parse(String)} method.
 */
@State(Scope.Thread)
public class PathParsingBenchmark {

    private String path1;
    private String path2;

    @Setup(Level.Invocation)
    @SuppressWarnings("StringOperationCanBeSimplified")
    public void setUp() {
        // Create a new String for paths every time to avoid constant folding.
        path1 = new String("/armeria/services/hello-world");
        path2 = new String("/armeria/services/goodbye-world");
    }

    @Benchmark
    public PathAndQuery normal() {
        return doNormal();
    }

    @Benchmark
    @Fork(jvmArgsAppend = "-Dcom.linecorp.armeria.parsedPathCache=off")
    public PathAndQuery normal_cacheDisabled() {
        return doNormal();
    }

    private PathAndQuery doNormal() {
        final PathAndQuery parsed = PathAndQuery.parse(path1);
        parsed.storeInCache(path1);
        return parsed;
    }

    @Benchmark
    public PathAndQuery cachedAndNotCached(Blackhole bh) {
        return doCachedAndNotCached(bh);
    }

    @Benchmark
    @Fork(jvmArgsAppend = "-Dcom.linecorp.armeria.parsedPathCache=off")
    public PathAndQuery cachedAndNotCached_cacheDisabled(Blackhole bh) {
        return doCachedAndNotCached(bh);
    }

    private PathAndQuery doCachedAndNotCached(Blackhole bh) {
        final PathAndQuery parsed = PathAndQuery.parse(path1);
        parsed.storeInCache(path1);
        final PathAndQuery parsed2 = PathAndQuery.parse(path2);
        bh.consume(parsed2);
        return parsed;
    }
}

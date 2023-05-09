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

import com.linecorp.armeria.common.RequestTarget;

/**
 * Microbenchmarks for {@link RequestTarget}.
 */
@State(Scope.Thread)
public class RequestTargetBenchmark {

    private static final String NO_CACHE_JVM_OPTS = "-Dcom.linecorp.armeria.parsedPathCacheSpec=off";

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
    public RequestTarget serverCached() {
        return doServer();
    }

    @Benchmark
    @Fork(jvmArgsAppend = NO_CACHE_JVM_OPTS)
    public RequestTarget serverUncached() {
        return doServer();
    }

    private RequestTarget doServer() {
        final RequestTarget parsed = RequestTarget.forServer(path1);
        RequestTargetCache.putForServer(path1, parsed);
        return parsed;
    }

    @Benchmark
    public RequestTarget serverCachedAndUncached(Blackhole bh) {
        return doServerCachedAndUncached(bh);
    }

    @Benchmark
    @Fork(jvmArgsAppend = NO_CACHE_JVM_OPTS)
    public RequestTarget serverUncachedAndUncached(Blackhole bh) {
        return doServerCachedAndUncached(bh);
    }

    private RequestTarget doServerCachedAndUncached(Blackhole bh) {
        final RequestTarget parsed = RequestTarget.forServer(path1);
        RequestTargetCache.putForServer(path1, parsed);
        final RequestTarget parsed2 = RequestTarget.forServer(path2);
        bh.consume(parsed2);
        return parsed;
    }

    @Benchmark
    public RequestTarget clientCached() {
        return doServer();
    }

    @Benchmark
    @Fork(jvmArgsAppend = NO_CACHE_JVM_OPTS)
    public RequestTarget clientUncached() {
        return doClient();
    }

    private RequestTarget doClient() {
        final RequestTarget parsed = RequestTarget.forClient(path1);
        RequestTargetCache.putForClient(path1, parsed);
        return parsed;
    }

    @Benchmark
    public RequestTarget clientCachedAndUncached(Blackhole bh) {
        return doClientCachedAndUncached(bh);
    }

    @Benchmark
    @Fork(jvmArgsAppend = NO_CACHE_JVM_OPTS)
    public RequestTarget clientUncachedAndUncached(Blackhole bh) {
        return doClientCachedAndUncached(bh);
    }

    private RequestTarget doClientCachedAndUncached(Blackhole bh) {
        final RequestTarget parsed = RequestTarget.forClient(path1);
        RequestTargetCache.putForClient(path1, parsed);
        final RequestTarget parsed2 = RequestTarget.forClient(path2);
        bh.consume(parsed2);
        return parsed;
    }
}

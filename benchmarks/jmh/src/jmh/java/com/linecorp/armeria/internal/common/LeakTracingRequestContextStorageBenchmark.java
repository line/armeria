/*
 * Copyright 2022 LINE Corporation
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

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Microbenchmarks for LeakTracingRequestContextStorage.
 */
public class LeakTracingRequestContextStorageBenchmark {

    private static final RequestContextStorage threadLocalReqCtxStorage =
            RequestContextStorage.threadLocal();
    private static final RequestContextStorage neverSample =
            new LeakTracingRequestContextStorage(threadLocalReqCtxStorage, Sampler.never());
    private static final RequestContextStorage rateLimited1 =
            new LeakTracingRequestContextStorage(threadLocalReqCtxStorage, Sampler.of("rate-limited=1"));
    private static final RequestContextStorage rateLimited10 =
            new LeakTracingRequestContextStorage(threadLocalReqCtxStorage, Sampler.of("rate-limited=10"));
    private static final RequestContextStorage random1 =
            new LeakTracingRequestContextStorage(threadLocalReqCtxStorage, Sampler.of("random=0.01"));
    private static final RequestContextStorage random10 =
            new LeakTracingRequestContextStorage(threadLocalReqCtxStorage, Sampler.of("random=0.10"));
    private static final RequestContextStorage alwaysSample =
            new LeakTracingRequestContextStorage(threadLocalReqCtxStorage, Sampler.always());
    private static final RequestContext reqCtx = newCtx("/");

    private static ServiceRequestContext newCtx(String path) {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, path))
                                    .build();
    }

    @Benchmark
    public void baseline_threadLocal() {
        final RequestContext oldCtx = threadLocalReqCtxStorage.push(reqCtx);
        threadLocalReqCtxStorage.pop(reqCtx, oldCtx);
    }

    @Benchmark
    public void leakTracing_never_sample() {
        final RequestContext oldCtx = neverSample.push(reqCtx);
        neverSample.pop(reqCtx, oldCtx);
    }

    @Benchmark
    public void leakTracing_rateLimited_1() {
        final RequestContext oldCtx = rateLimited1.push(reqCtx);
        rateLimited1.pop(reqCtx, oldCtx);
    }

    @Benchmark
    public void leakTracing_rateLimited_10() {
        final RequestContext oldCtx = rateLimited10.push(reqCtx);
        rateLimited10.pop(reqCtx, oldCtx);
    }

    @Benchmark
    public void leakTracing_random_1() {
        final RequestContext oldCtx = random1.push(reqCtx);
        random1.pop(reqCtx, oldCtx);
    }

    @Benchmark
    public void leakTracing_random_10() {
        final RequestContext oldCtx = random10.push(reqCtx);
        random10.pop(reqCtx, oldCtx);
    }

    @Benchmark
    public void leakTracing_always_sample() {
        final RequestContext oldCtx = alwaysSample.push(reqCtx);
        alwaysSample.pop(reqCtx, oldCtx);
    }
}

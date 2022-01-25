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

package com.linecorp.armeria.grpc.shared;

import static com.linecorp.armeria.grpc.shared.GithubApiService.SEARCH_RESPONSE;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Empty;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.grpc.GithubApi.SearchResponse;
import com.linecorp.armeria.grpc.GithubServiceGrpc;
import com.linecorp.armeria.grpc.GithubServiceGrpc.GithubServiceBlockingStub;
import com.linecorp.armeria.grpc.GithubServiceGrpc.GithubServiceFutureStub;
import com.linecorp.armeria.shared.AsyncCounters;

import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;

/**
 * The {@link SimpleBenchmarkBase} contains the shared logic for benchmarking grpc.
 */
@State(Scope.Benchmark)
public abstract class SimpleBenchmarkBase {

    /**
     * The port the benchmark's server is listening on.
     */
    protected abstract int port();

    /**
     * The normal {@link GithubServiceBlockingStub} for the benchmark. The okhttp version will be set up in this
     * class as it's the same for both upstream and downstream.
     */
    protected abstract GithubServiceBlockingStub normalClient();

    /**
     * The normal {@link GithubServiceFutureStub} for the benchmark. The okhttp version will be set up in this
     * class as it's the same for both upstream and downstream.
     */
    protected abstract GithubServiceFutureStub normalFutureClient();

    /**
     * Benchmark initialization logic.
     */
    protected abstract void setUp() throws Exception;

    /**
     * Benchmark teardown logic.
     */
    protected abstract void tearDown() throws Exception;

    private ManagedChannel okhttpChannel;
    private GithubServiceBlockingStub githubApiOkhttpClient;
    private GithubServiceFutureStub githubApiOkhttpFutureClient;

    @Param
    private ClientType clientType;

    @Setup
    public void start() throws Exception {
        setUp();
        okhttpChannel = OkHttpChannelBuilder.forAddress("127.0.0.1", port())
                                            .usePlaintext()
                                            .directExecutor()
                                            .build();
        githubApiOkhttpClient = GithubServiceGrpc.newBlockingStub(okhttpChannel);
        githubApiOkhttpFutureClient = GithubServiceGrpc.newFutureStub(okhttpChannel);
    }

    @TearDown
    public void stop() throws Exception {
        okhttpChannel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        tearDown();
    }

    @Benchmark
    public SearchResponse simple() throws Exception {
        return stub().simple(SEARCH_RESPONSE);
    }

    @Benchmark
    public void simpleNonBlocking(AsyncCounters counters) throws Exception {
        counters.incrementCurrentRequests();
        Futures.addCallback(
                futureStub().simple(SEARCH_RESPONSE),
                counterIncrementingFutureCallback(counters),
                MoreExecutors.directExecutor());
    }

    @Benchmark
    public Empty empty() throws Exception {
        return stub().empty(Empty.getDefaultInstance());
    }

    @Benchmark
    public void emptyNonBlocking(AsyncCounters counters) throws Exception {
        counters.incrementCurrentRequests();
        Futures.addCallback(
                futureStub().empty(Empty.getDefaultInstance()),
                counterIncrementingFutureCallback(counters),
                MoreExecutors.directExecutor());
    }

    private GithubServiceBlockingStub stub() {
        return clientType == ClientType.NORMAL ? normalClient() : githubApiOkhttpClient;
    }

    private GithubServiceFutureStub futureStub() {
        return clientType == ClientType.NORMAL ? normalFutureClient() : githubApiOkhttpFutureClient;
    }

    private static <T> FutureCallback<T> counterIncrementingFutureCallback(AsyncCounters counters) {
        return new FutureCallback<T>() {
            @Override
            public void onSuccess(@Nullable T result) {
                counters.decrementCurrentRequests();
                counters.incrementNumSuccesses();
            }

            @Override
            public void onFailure(Throwable t) {
                counters.decrementCurrentRequests();
                counters.incrementNumFailures();
            }
        };
    }
}

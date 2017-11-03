/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Empty;

import com.linecorp.armeria.benchmarks.GithubApi.SearchResponse;
import com.linecorp.armeria.benchmarks.GithubServiceGrpc;
import com.linecorp.armeria.benchmarks.GithubServiceGrpc.GithubServiceBlockingStub;
import com.linecorp.armeria.benchmarks.GithubServiceGrpc.GithubServiceFutureStub;

import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;

@State(Scope.Benchmark)
public abstract class SimpleBenchmarkBase {

    @AuxCounters
    @State(Scope.Thread)
    public static class Counters {
        AtomicLong numRequests = new AtomicLong();
        AtomicLong numFailures = new AtomicLong();

        public long numRequests() {
            return numRequests.get();
        }

        public long numFailures() {
            return this.numFailures.get();
        }

        @Setup(Level.Iteration)
        public void reset() {
            numRequests.set(0);
            numFailures.set(0);
        }
    }

    /**
     * The port the benchmark's server is listening on.
     */
    abstract protected int port();

    /**
     * The normal {@link GithubServiceBlockingStub} for the benchmark. The okhttp version will be set up in this
     * class as it's the same for both upstream and downstream.
     */
    abstract protected GithubServiceBlockingStub normalClient();

    /**
     * The normal {@link GithubServiceFutureStub} for the benchmark. The okhttp version will be set up in this
     * class as it's the same for both upstream and downstream.
     */
    abstract protected GithubServiceFutureStub normalFutureClient();

    /**
     * Benchmark initialization logic.
     */
    abstract protected void setUp() throws Exception;

    /**
     * Benchmark teardown logic.
     */
    abstract protected void tearDown() throws Exception;

    private ManagedChannel okhttpChannel;
    private GithubServiceBlockingStub githubApiOkhttpClient;
    private GithubServiceFutureStub githubApiOkhttpFutureClient;

    @Param
    public ClientType clientType = ClientType.NORMAL;

    @Setup
    public void start() throws Exception {
        setUp();
        okhttpChannel = OkHttpChannelBuilder.forAddress("127.0.0.1", port())
                                            .usePlaintext(true)
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
    public void simpleNonBlocking(Counters counters) throws Exception {
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
    public void emptyNonBlocking(Counters counters) throws Exception {
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

    private static <T> FutureCallback<T> counterIncrementingFutureCallback(Counters counters) {
        return new FutureCallback<T>() {
            @Override
            public void onSuccess(@Nullable T result) {
                counters.numRequests.incrementAndGet();
            }

            @Override
            public void onFailure(Throwable t) {
                counters.numRequests.incrementAndGet();
                counters.numFailures.incrementAndGet();
            }
        };
    }
}

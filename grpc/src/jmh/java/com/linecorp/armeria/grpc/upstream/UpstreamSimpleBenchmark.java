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

package com.linecorp.armeria.grpc.upstream;

import static com.linecorp.armeria.grpc.shared.GithubApiService.SEARCH_RESPONSE;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import com.google.protobuf.Empty;

import com.linecorp.armeria.benchmarks.GithubServiceGrpc;
import com.linecorp.armeria.benchmarks.GithubServiceGrpc.GithubServiceBlockingStub;
import com.linecorp.armeria.grpc.shared.GithubApiService;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

@State(Scope.Benchmark)
public class UpstreamSimpleBenchmark {

    private Server server;
    private ManagedChannel channel;
    private GithubServiceBlockingStub githubApiClient;

    @Setup
    public void startServer() throws Exception {
        server = ServerBuilder.forPort(0)
                              .addService(new GithubApiService())
                              .directExecutor()
                              .build();
        server.start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort())
                                       .directExecutor()
                                       .usePlaintext(true)
                                       .build();
        githubApiClient = GithubServiceGrpc.newBlockingStub(channel);
    }

    @TearDown
    public void stopServer() throws Exception {
        server.shutdown().awaitTermination();
        channel.shutdown().awaitTermination(10, TimeUnit.SECONDS);
    }

    @Benchmark
    public void simple(Blackhole bh) throws Exception {
        bh.consume(githubApiClient.simple(SEARCH_RESPONSE));
    }

    @Benchmark
    public void empty(Blackhole bh) throws Exception {
        bh.consume(githubApiClient.empty(Empty.getDefaultInstance()));
    }
}

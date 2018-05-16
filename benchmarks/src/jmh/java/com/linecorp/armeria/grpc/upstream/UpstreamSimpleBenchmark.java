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

package com.linecorp.armeria.grpc.upstream;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.grpc.GithubServiceGrpc;
import com.linecorp.armeria.grpc.GithubServiceGrpc.GithubServiceBlockingStub;
import com.linecorp.armeria.grpc.GithubServiceGrpc.GithubServiceFutureStub;
import com.linecorp.armeria.grpc.shared.GithubApiService;
import com.linecorp.armeria.grpc.shared.SimpleBenchmarkBase;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

@State(Scope.Benchmark)
public class UpstreamSimpleBenchmark extends SimpleBenchmarkBase {

    private Server server;
    private ManagedChannel channel;
    private GithubServiceBlockingStub githubApiClient;
    private GithubServiceFutureStub githubApiFutureClient;

    @Override
    protected int port() {
        return server.getPort();
    }

    @Override
    protected GithubServiceBlockingStub normalClient() {
        return githubApiClient;
    }

    @Override
    protected GithubServiceFutureStub normalFutureClient() {
        return githubApiFutureClient;
    }

    @Override
    public void setUp() throws Exception {
        server = ServerBuilder.forPort(0)
                              .addService(new GithubApiService())
                              .directExecutor()
                              .build();
        server.start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port())
                                       .directExecutor()
                                       .usePlaintext()
                                       .build();
        githubApiClient = GithubServiceGrpc.newBlockingStub(channel);
        githubApiFutureClient = GithubServiceGrpc.newFutureStub(channel);
    }

    @Override
    public void tearDown() throws Exception {
        channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        server.shutdown().awaitTermination();
    }
}

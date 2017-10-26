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

package com.linecorp.armeria.grpc.downstream;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.grpc.shared.GithubApiService.SEARCH_RESPONSE;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import com.google.protobuf.Empty;

import com.linecorp.armeria.benchmarks.GithubServiceGrpc.GithubServiceBlockingStub;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.grpc.shared.GithubApiService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

@State(Scope.Benchmark)
public class DownstreamSimpleBenchmark {

    private Server server;
    private GithubServiceBlockingStub githubApiClient;

    @Setup
    public void startServer() throws Exception {
        server = new ServerBuilder()
                .port(0, HTTP)
                .serviceUnder("/", new GrpcServiceBuilder().addService(new GithubApiService()).build())
                .build();
        server.start().join();
        ServerPort httpPort = server.activePorts().values().stream()
                                    .filter(p1 -> p1.protocol() == HTTP).findAny()
                                    .get();
        githubApiClient = Clients.newClient(
                "gproto+http://127.0.0.1:" + httpPort.localAddress().getPort() + "/",
                GithubServiceBlockingStub.class);
    }

    @TearDown
    public void stopServer() throws Exception {
        server.stop().join();
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

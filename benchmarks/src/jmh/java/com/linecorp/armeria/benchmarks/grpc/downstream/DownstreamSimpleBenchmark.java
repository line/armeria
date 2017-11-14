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

package com.linecorp.armeria.benchmarks.grpc.downstream;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.benchmarks.grpc.GithubServiceGrpc.GithubServiceBlockingStub;
import com.linecorp.armeria.benchmarks.grpc.GithubServiceGrpc.GithubServiceFutureStub;
import com.linecorp.armeria.benchmarks.grpc.shared.GithubApiService;
import com.linecorp.armeria.benchmarks.grpc.shared.SimpleBenchmarkBase;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

@State(Scope.Benchmark)
public class DownstreamSimpleBenchmark extends SimpleBenchmarkBase {

    private Server server;
    private GithubServiceBlockingStub githubApiClient;
    private GithubServiceFutureStub githubApiFutureClient;

    @Override
    protected int port() {
        ServerPort httpPort = server.activePorts().values().stream()
                                    .filter(p1 -> p1.protocol() == HTTP).findAny()
                                    .get();
        return httpPort.localAddress().getPort();
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
    protected void setUp() throws Exception {
        server = new ServerBuilder()
                .port(0, HTTP)
                .serviceUnder("/", new GrpcServiceBuilder().addService(new GithubApiService()).build())
                .build();
        server.start().join();
        final String url = "gproto+http://127.0.0.1:" + port() + "/";
        githubApiClient = Clients.newClient(url, GithubServiceBlockingStub.class);
        githubApiFutureClient = Clients.newClient(url, GithubServiceFutureStub.class);
    }

    @Override
    protected void tearDown() throws Exception {
        server.stop().join();
    }

    // public static void main(String[] args) throws Exception {
    //     DownstreamSimpleBenchmark benchmark = new DownstreamSimpleBenchmark();
    //     benchmark.start();
    //     System.out.println(benchmark.simple());
    //     System.out.println(benchmark.empty());
    //     benchmark.stop();
    // }
}

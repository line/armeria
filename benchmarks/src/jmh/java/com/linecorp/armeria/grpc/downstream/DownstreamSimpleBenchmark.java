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

package com.linecorp.armeria.grpc.downstream;

import java.time.Duration;

import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.grpc.GithubServiceGrpc.GithubServiceBlockingStub;
import com.linecorp.armeria.grpc.GithubServiceGrpc.GithubServiceFutureStub;
import com.linecorp.armeria.grpc.shared.GithubApiService;
import com.linecorp.armeria.grpc.shared.SimpleBenchmarkBase;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;

@State(Scope.Benchmark)
@Fork(jvmArgsAppend = "-Dcom.linecorp.armeria.cachedHeaders=:authority,:scheme,:method,accept-encoding," +
                      "content-type,:path,user-agent,grpc-accept-encoding,te")
public class DownstreamSimpleBenchmark extends SimpleBenchmarkBase {

    private Server server;
    private GithubServiceBlockingStub githubApiClient;
    private GithubServiceFutureStub githubApiFutureClient;

    @Override
    protected int port() {
        return server.activeLocalPort(SessionProtocol.HTTP);
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
        server = Server.builder()
                       .serviceUnder("/",
                                     GrpcService.builder()
                                                .addService(new GithubApiService()).build())
                                                .requestTimeout(Duration.ZERO)
                                                .meterRegistry(NoopMeterRegistry.get())
                                                .build();
        server.start().join();
        final String url = "gproto+http://127.0.0.1:" + port() + '/';
        githubApiClient = Clients.newClient(url, GithubServiceBlockingStub.class);
        githubApiFutureClient = Clients.newClient(url, GithubServiceFutureStub.class);
    }

    @Override
    protected void tearDown() throws Exception {
        server.stop().join();
    }

    // public static void main(String[] args) throws Exception {
    //      DownstreamSimpleBenchmark benchmark = new DownstreamSimpleBenchmark();
    //      benchmark.start();
    //      for (long i = 0; i < Long.MAX_VALUE; i++) {
    //          benchmark.empty();
    //      }
    //      benchmark.stop();
    // }
}

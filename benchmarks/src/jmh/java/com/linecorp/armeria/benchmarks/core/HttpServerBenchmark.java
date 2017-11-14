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

package com.linecorp.armeria.benchmarks.core;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;

import java.util.concurrent.atomic.AtomicLong;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;

@State(Scope.Benchmark)
public class HttpServerBenchmark {

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

    private Server server;
    private HttpClient httpClient;

    @Setup
    public void startServer() throws Exception {
        server = new ServerBuilder()
                .port(0, HTTP)
                .service("/empty", ((ctx, req) -> HttpResponse.of(HttpStatus.OK)))
                .build();
        server.start().join();
        ServerPort httpPort = server.activePorts().values().stream()
                                    .filter(p1 -> p1.protocol() == HTTP).findAny()
                                    .get();
        httpClient = Clients.newClient("none+http://127.0.0.1:" + httpPort.localAddress().getPort() + "/",
                                       HttpClient.class);
    }

    @TearDown
    public void stopServer() throws Exception {
        server.stop().join();
    }

    @Benchmark
    public void empty(Blackhole bh, Counters counters) throws Exception {
        bh.consume(
                httpClient.get("/empty")
                          .aggregate()
                          .whenComplete((msg, t) -> {
                              counters.numRequests.incrementAndGet();
                              if (t != null) {
                                  counters.numFailures.incrementAndGet();
                              }
                          }));
    }
}

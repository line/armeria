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

package com.linecorp.armeria.core;

import java.time.Duration;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.shared.AsyncCounters;

/**
 * Microbenchmarks of a {@link Server}.
 */
@State(Scope.Benchmark)
public class HttpServerBenchmark {

    // JMH bug prevents it from using enums that override toString() (it should use name() instead...).
    public enum Protocol {
        H2C(SessionProtocol.H2C),
        H1C(SessionProtocol.H1C);

        private final SessionProtocol sessionProtocol;

        Protocol(SessionProtocol sessionProtocol) {
            this.sessionProtocol = sessionProtocol;
        }

        String uriText() {
            return sessionProtocol.uriText();
        }
    }

    private Server server;
    private WebClient webClient;

    @Param
    private Protocol protocol;

    @Setup
    public void startServer() throws Exception {
        server = Server.builder()
                       .service("/empty", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                       .requestTimeout(Duration.ZERO)
                       .meterRegistry(NoopMeterRegistry.get())
                       .build();
        server.start().join();
        final ServerPort httpPort = server.activePorts().values().stream()
                                          .filter(ServerPort::hasHttp).findAny()
                                          .get();
        webClient = Clients.newClient("none+" + protocol.uriText() + "://127.0.0.1:" +
                                      httpPort.localAddress().getPort() + '/',
                                      WebClient.class);
    }

    @TearDown
    public void stopServer() throws Exception {
        server.stop().join();
    }

    @Benchmark
    public void empty(Blackhole bh, AsyncCounters counters) throws Exception {
        counters.incrementCurrentRequests();
        bh.consume(
                webClient.get("/empty")
                         .aggregate()
                         .handle((msg, t) -> {
                             counters.decrementCurrentRequests();
                             if (t != null) {
                                 counters.incrementNumFailures();
                             } else {
                                 counters.incrementNumSuccesses();
                             }
                             return null;
                         })
                         .get());
    }
}

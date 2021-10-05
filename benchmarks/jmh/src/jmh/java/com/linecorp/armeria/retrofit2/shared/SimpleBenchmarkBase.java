/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.retrofit2.shared;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;

/**
 * The {@link SimpleBenchmarkBase} contains the shared logic for benchmarking retrofit2.
 */
@State(Scope.Benchmark)
public abstract class SimpleBenchmarkBase {

    private Server server;
    private SimpleBenchmarkClient client;

    @Setup
    public void start() throws Exception {
        server = Server.builder()
                       .https(0)
                       .service("/empty", (ctx, req) -> HttpResponse.of("\"\""))
                       .tlsSelfSigned()
                       .build();
        server.start().join();
        client = newClient();
    }

    @TearDown
    public void stop() {
        server.stop().join();
    }

    protected abstract SimpleBenchmarkClient newClient() throws Exception;

    protected String baseUrl() {
        return "https://127.0.0.1:" + server.activeLocalPort(SessionProtocol.HTTP);
    }

    @Benchmark
    public void empty() {
        client.empty().join();
    }
}

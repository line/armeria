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

package com.linecorp.armeria.core.client.retry;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;

/**
 * The base for {@link WithDuplicator} and {@link WithoutDuplicator} microbenchmarks.
 */
@State(Scope.Benchmark)
public abstract class RetryingClientBase {

    private Server server;
    private WebClient client;

    @Setup
    public void start() {
        server = Server.builder()
                       .http(0)
                       .service("/empty", (ctx, req) -> HttpResponse.of("\"\""))
                       .build();
        server.start().join();
        client = newClient();
    }

    @TearDown
    public void stop() {
        server.stop().join();
    }

    protected abstract WebClient newClient();

    protected final String baseUrl() {
        return "h2c://127.0.0.1:" + server.activeLocalPort(SessionProtocol.HTTP);
    }

    @Benchmark
    public void empty() {
        client.get("/empty").aggregate().join();
    }
}

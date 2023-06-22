/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.annotation.Get;

@State(Scope.Benchmark)
public class AnnotatedServiceBenchmark {

    @Nullable
    private Server server;
    @Nullable
    private WebClient client;

    @Setup(Level.Trial)
    public void startServer() {
        server = Server.builder()
                .http(0)
                .service("/functional", (ctx, req) -> HttpResponse.of(200))
                .annotatedService(new Object() {
                    @Get("/annotated")
                    public HttpResponse get() {
                        return HttpResponse.of(200);
                    }
                })
                .build();
        server.start().join();
        client = WebClient.of("h2c://127.0.0.1:" + server.activeLocalPort());
    }

    @TearDown(Level.Trial)
    public void stopServer() {
        assert server != null;
        server.stop().join();
    }

    @Benchmark
    public void baseline(Blackhole bh) {
        test(bh, "/functional");
    }

    @Benchmark
    public void annotated(Blackhole bh) {
        test(bh, "/annotated");
    }

    private void test(Blackhole bh, String path) {
        assert client != null;
        final List<CompletableFuture<Void>> futures = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            futures.add(client.get(path).subscribe());
        }
        futures.forEach(f -> bh.consume(f.join()));
    }
}

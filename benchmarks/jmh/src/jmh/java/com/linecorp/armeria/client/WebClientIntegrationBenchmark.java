/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.server.Server;

@State(Scope.Benchmark)
public class WebClientIntegrationBenchmark {

    private Server server;
    private WebClient client;
    private String body;

    @Param({ "false", "true" })
    private boolean useRetry;

    @Setup
    public void setUp() {
        final Server server =
                Server.builder()
                      .service("/get", (ctx, req) -> {
                          return HttpResponse.of("Hello! Armeria");
                      }).service("/post", (ctx, req) -> {
                          return HttpResponse.of(req.aggregate().thenApply(agg -> {
                              return HttpResponse.of(agg.contentUtf8());
                          }));
                      }).build();
        server.start().join();
        this.server = server;
        if (useRetry) {
            client = WebClient.builder("http://127.0.0.1:" + server.activeLocalPort())
                              .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
                              .build();
        } else {
            client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort());
        }
        body = Strings.repeat("a", 1000);
    }

    @TearDown
    public void tearDown() {
        server.stop().join();
    }

    @Benchmark
    public ResponseEntity<String> getRequest() {
        return execute(HttpMethod.GET, "/get");
    }

    @Benchmark
    public ResponseEntity<String> postRequest() {
        return execute(HttpMethod.POST, "/post");
    }

    private ResponseEntity<String> execute(HttpMethod method, String path) {
        final WebClientRequestPreparation prepare = client.prepare()
                                                          .method(method).path(path);
        if (method == HttpMethod.POST) {
            prepare.content(body);
        }
        return prepare.asString().execute().join();
    }
}

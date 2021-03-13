/*
 * Copyright 2019 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

class TestReconfigurableServer {

    @Test
    public void test_reconfiguration_of_server() throws Exception {
        final ServerBuilder sb = Server.builder();
        sb.http(8080);

        sb.service("/test1", (ctx, req) -> HttpResponse.of("Hello, world!"));

        sb.service("/test11/{name}", (ctx, req) -> {
            final String param = ctx.pathParam("name");
            return HttpResponse.of("Hello, " + param.toUpperCase());
        });

        final Server server = sb.build();

        final CompletableFuture<Void> future = server.start();
        Thread.sleep(1000);

        try (ClientFactory factory =
                     ClientFactory.builder().build()) {
            final WebClient client = WebClient.builder()
                    .factory(factory)
                    .build();
            final AggregatedHttpResponse response = client.get("http://localhost:8080/test1").aggregate().get();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("Hello, world!");
        }

        Thread.sleep(1000);
        System.out.println("Configuring new server");
        server.reconfigure(serverBuilder  -> {
            // Replace the entire routes with the following two services.
            serverBuilder.service("/test2", (ctx, req) -> HttpResponse.of("Hello, world!"));

            serverBuilder.service("/test2/{name}",
                                 (ctx, req) ->
                                 HttpResponse.of("Hello, " + ctx.pathParam("name").toUpperCase()));
        });

        try (ClientFactory factory =
                     ClientFactory.builder().build()) {
            final WebClient client = WebClient.builder()
                    .factory(factory)
                    .build();
            final AggregatedHttpResponse response = client.get("http://localhost:8080/test2")
                                                    .aggregate()
                                                    .get();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("Hello, world!");

            final AggregatedHttpResponse response1 = client.get("http://localhost:8080/test2/world")
                                                     .aggregate()
                                                     .get();

            assertThat(response1.status()).isEqualTo(HttpStatus.OK);
            assertThat(response1.contentUtf8()).isEqualTo("Hello, WORLD");

            // Tests that original service configurations are no longer active.
            final AggregatedHttpResponse response3 = client.get("http://localhost:8080/test1")
                                                     .aggregate()
                                                     .get();
            assertThat(response3.status()).isEqualTo(HttpStatus.NOT_FOUND);

            final AggregatedHttpResponse response4 = client.get("http://localhost:8080/test1/world")
                                                     .aggregate()
                                                     .get();

            assertThat(response4.status()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        future.join();
    }

    @Test
    public void test_we_dont_reconfigure_empty_serviceconfig() throws Exception {
        final ServerBuilder sb = Server.builder();
        sb.http(8081);
        sb.service("/test1", (ctx, req) -> HttpResponse.of("Hello, world!"));

        final Server server = sb.build();

        final CompletableFuture<Void> future = server.start();
        Thread.sleep(100);
        try (ClientFactory factory =
                     ClientFactory.builder().build()) {
            final WebClient client = WebClient.builder()
                    .factory(factory)
                    .build();

            final AggregatedHttpResponse res = client.get("http://localhost:8081/test1").aggregate().get();
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentUtf8()).isEqualTo("Hello, world!");
        }

        assertThatThrownBy(() -> server.reconfigure(serverBuilder  -> {
            // This should not work since we do not allow empty service configuration.
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("no services in the server");
    }
}

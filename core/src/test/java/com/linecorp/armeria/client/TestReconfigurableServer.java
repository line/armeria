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

import java.util.concurrent.CompletableFuture;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReconfigurableServer {

    @Test
    public void test_reconfiguration_of_server() throws Exception {
        final ServerBuilder sb = Server.builder();
        sb.http(8080);

        sb.service("/test1", (ctx, req) -> {
            return HttpResponse.of("Hello, world!");
        });

        sb.service("/test11/{name}", (ctx, req) -> {
            String param = ctx.pathParam("name");
            return HttpResponse.of("Hello, "+param.toUpperCase());
        });

        final Server server = sb.build();

        final CompletableFuture<Void> future = server.start();
        Thread.sleep(1000);
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet("http://localhost:8080/test1"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("Hello, world!");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet("http://localhost:8080/test11/world"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("Hello, WORLD");
            }
        }

        Thread.sleep(1000);
        System.out.println("Configuring new server");
        server.reconfigure(serverBuilder  -> {
            // Replace the entire routes with the following two services.
            serverBuilder.service("/test2", (ctx, req) -> {
                return HttpResponse.of("Hello, world!");
            });

            serverBuilder.service("/test2/{name}", (ctx, req) -> {
                return HttpResponse.of("Hello, " + ctx.pathParam("name").toUpperCase());
            });
        });

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet("http://localhost:8080/test2"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("Hello, world!");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet("http://localhost:8080/test2/world"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("Hello, WORLD");
            }

            // Tests that original service configurations are no longer active.
            try (CloseableHttpResponse res = hc.execute(new HttpGet("http://localhost:8080/test1"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 404 Not Found");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet("http://localhost:8080/test1/world"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 404 Not Found");
            }
        }
        future.join();
    }

    @Test
    public void test_we_dont_reconfigure_empty_serviceconfig() throws Exception {
        final ServerBuilder sb = Server.builder();
        sb.http(8081);
        sb.service("/test1", (ctx, req) -> {
            return HttpResponse.of("Hello, world!");
        });

        final Server server = sb.build();

        final CompletableFuture<Void> future = server.start();
        Thread.sleep(100);
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet("http://localhost:8081/test1"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("Hello, world!");
            }
        }

        assertThatThrownBy(() -> server.reconfigure(serverBuilder  -> {
            // This should not work since we do not allow empty service configuration.
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("no services in the server");
    }
}

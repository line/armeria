/*
 * Copyright 2021 LINE Corporation
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
        sb.http(9009);

        sb.service("/test1", (ctx, req) -> HttpResponse.of("Hello, world!"));

        sb.service("/test11/{name}", (ctx, req) -> {
            final String param = ctx.pathParam("name");
            return HttpResponse.of("Hello, " + param.toUpperCase());
        });

        final Server server = sb.build();

        server.start().join();

        final WebClient originalClient = WebClient.of("http://localhost:" + server.activeLocalPort());
        final AggregatedHttpResponse response = originalClient.get("/test1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("Hello, world!");

        final AggregatedHttpResponse response11 = originalClient.get("/test11/world").aggregate().join();
        assertThat(response11.status()).isEqualTo(HttpStatus.OK);
        assertThat(response11.contentUtf8()).isEqualTo("Hello, WORLD");

        Thread.sleep(1000); // Some time goes by now want to reconfigure new server
        System.out.println("Configuring new server");
        server.reconfigure(serverBuilder  -> {
            // Replace the entire routes with the following two services.
            serverBuilder.service("/test2", (ctx, req) -> HttpResponse.of("Hello, world!"));

            serverBuilder.service("/test2/{name}",
                    (ctx, req) ->
                            HttpResponse.of("Hello, " + ctx.pathParam("name").toUpperCase()));
        });

        // retry calling the original service. Should return 404.
        final AggregatedHttpResponse response1 = originalClient.get("/test11/world").aggregate().join();
        assertThat(response1.status()).isEqualTo(HttpStatus.NOT_FOUND);

        final AggregatedHttpResponse res = originalClient.get("/test2").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("Hello, world!");

        //Open new connection to check if the newly reconfigured server is now operational
        // and old service endpoints are no longer visible.
        final WebClient client2 = WebClient.of("http://localhost:" + server.activeLocalPort());
        final AggregatedHttpResponse response2 = client2.get("/test2/world")
                .aggregate()
                .get();

        assertThat(response2.status()).isEqualTo(HttpStatus.OK);
        assertThat(response2.contentUtf8()).isEqualTo("Hello, WORLD");

        final AggregatedHttpResponse response22 = client2.get("/test2")
                .aggregate()
                .get();

        assertThat(response22.status()).isEqualTo(HttpStatus.OK);
        assertThat(response22.contentUtf8()).isEqualTo("Hello, world!");

        final AggregatedHttpResponse failedResponse = client2.get("/test1").aggregate().get();
        assertThat(failedResponse.status()).isEqualTo(HttpStatus.NOT_FOUND);

        // Tests that original service configurations are no longer active when you open a new connection
        // with the server.
        final WebClient client3 = WebClient.of("http://localhost:" + server.activeLocalPort());
        final AggregatedHttpResponse response3 = client3.get("/test1")
                .aggregate()
                .get();
        assertThat(response3.status()).isEqualTo(HttpStatus.NOT_FOUND);

        final AggregatedHttpResponse response4 = client3.get("/test1/world")
                .aggregate()
                .get();

        assertThat(response4.status()).isEqualTo(HttpStatus.NOT_FOUND);
        server.stop().join();
    }

    @Test
    public void test_we_dont_reconfigure_empty_serviceconfig() throws Exception {
        final ServerBuilder sb = Server.builder();
        sb.http(9010);
        sb.service("/test1", (ctx, req) -> HttpResponse.of("Hello, world!"));

        final Server server = sb.build();

        server.start().join();

        final WebClient client3 = WebClient.of("http://localhost:" + server.activeLocalPort());
        final AggregatedHttpResponse res = client3.get("/test1")
                .aggregate()
                .get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("Hello, world!");

        assertThatThrownBy(() -> server.reconfigure(serverBuilder  -> {
            // This should not work since we do not allow empty service configuration.
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("no services in the server");
    }

    @Test
    public void test_reconfiguration_of_https_server() throws Exception {
        final ServerBuilder sb = Server.builder();
        sb.https(9009);
        sb.tlsSelfSigned();

        sb.service("/test1", (ctx, req) -> HttpResponse.of("Hello, world!"));

        sb.service("/test11/{name}", (ctx, req) -> {
            final String param = ctx.pathParam("name");
            return HttpResponse.of("Hello, " + param.toUpperCase());
        });

        final Server server = sb.build();

        server.start().join();

        final WebClient originalClient = WebClient.builder("https://localhost:" + server.activeLocalPort())
                .factory(ClientFactory.insecure())
                .build();

        final AggregatedHttpResponse response = originalClient.get("/test1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("Hello, world!");

        final AggregatedHttpResponse response11 = originalClient.get("/test11/world").aggregate().join();
        assertThat(response11.status()).isEqualTo(HttpStatus.OK);
        assertThat(response11.contentUtf8()).isEqualTo("Hello, WORLD");

        Thread.sleep(1000); // Some time goes by now want to reconfigure new server
        System.out.println("Configuring new server");
        server.reconfigure(serverBuilder  -> {
            // Need to reconfigure ssl context.
            serverBuilder.tlsSelfSigned();
            // Replace the entire routes with the following two services.
            serverBuilder.service("/test2", (ctx, req) -> HttpResponse.of("Hello, world!"));

            serverBuilder.service("/test2/{name}",
                    (ctx, req) ->
                            HttpResponse.of("Hello, " + ctx.pathParam("name").toUpperCase()));
        });

        // retry calling the original service. Should return 404.
        final AggregatedHttpResponse response1 = originalClient.get("/test11/world").aggregate().join();
        assertThat(response1.status()).isEqualTo(HttpStatus.NOT_FOUND);

        final AggregatedHttpResponse res = originalClient.get("/test2").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("Hello, world!");

        //Open new connection to check if the newly reconfigured server is now operational
        // and old service endpoints are no longer visible.
        final WebClient client2 = WebClient.builder("https://localhost:" + server.activeLocalPort())
                .factory(ClientFactory.insecure())
                .build();

        final AggregatedHttpResponse response2 = client2.get("/test2/world")
                .aggregate()
                .get();

        assertThat(response2.status()).isEqualTo(HttpStatus.OK);
        assertThat(response2.contentUtf8()).isEqualTo("Hello, WORLD");

        final AggregatedHttpResponse response22 = client2.get("/test2")
                .aggregate()
                .get();

        assertThat(response22.status()).isEqualTo(HttpStatus.OK);
        assertThat(response22.contentUtf8()).isEqualTo("Hello, world!");

        final AggregatedHttpResponse failedResponse = client2.get("/test1").aggregate().get();
        assertThat(failedResponse.status()).isEqualTo(HttpStatus.NOT_FOUND);

        // Tests that original service configurations are no longer active when you open a new connection
        // with the server.
        final WebClient client3 = WebClient.builder("https://localhost:" + server.activeLocalPort())
                .factory(ClientFactory.insecure())
                .build();

        final AggregatedHttpResponse response3 = client3.get("/test1")
                .aggregate()
                .get();
        assertThat(response3.status()).isEqualTo(HttpStatus.NOT_FOUND);

        final AggregatedHttpResponse response4 = client3.get("/test1/world")
                .aggregate()
                .get();

        assertThat(response4.status()).isEqualTo(HttpStatus.NOT_FOUND);
        server.stop().join();
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.testing.FlakyTest;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@FlakyTest
class BaseContextPathTest {
    private static int normalServerPort;
    private static int fooHostPort;
    private static int barHostPort;

    private static ClientFactory clientFactory;

    @RegisterExtension
    static ServerExtension serverWithPortMapping = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            try (ServerSocket ss = new ServerSocket(0)) {
                normalServerPort = ss.getLocalPort();
            }
            try (ServerSocket ss = new ServerSocket(0)) {
                barHostPort = ss.getLocalPort();
            }
            try (ServerSocket ss = new ServerSocket(0)) {
                fooHostPort = ss.getLocalPort();
            }

            sb.http(normalServerPort)
              .http(fooHostPort)
              .http(barHostPort)
              .service("/foo", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .service("/api/v1/bar", (ctx, req) -> HttpResponse.of(HttpStatus.ACCEPTED))
              .decorator("/deco", ((delegate, ctx, req) -> HttpResponse.of(HttpStatus.OK)))
              .contextPath("/admin")
              .service("/foo", (ctx, req) -> HttpResponse.of(ctx.path()))
              .and()
              .baseContextPath("/home")
              // 2
              .virtualHost("*.foo.com:" + fooHostPort)
              .baseContextPath("/api/v1")
              .decorator("/deco", ((delegate, ctx, req) -> HttpResponse.of(HttpStatus.OK)))
              .service("/good", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .service("/bar", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .contextPath("/admin")
              .service("/foo", (ctx, req) -> HttpResponse.of(ctx.path()))
              .and()
              .and()
              // 3
              .virtualHost("*.bar.com:" + barHostPort)
              .baseContextPath("/api/v2")
              .service("/world", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .service("/bad", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .and()
              // 4
              .virtualHost("*.hostmap.com")
              .baseContextPath("/api/v3")
              .service("/me", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .service("/you", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .and()
              .build();
        }
    };

    @BeforeAll
    static void init() {
        clientFactory = ClientFactory.builder()
                                     .addressResolverGroupFactory(group -> MockAddressResolverGroup.localhost())
                                     .build();
    }

    @AfterAll
    static void destroy() {
        clientFactory.closeAsync();
    }

    @Test
    void defaultVirtualHost() {
        final WebClient defaultClient = WebClient.of("http://127.0.0.1:" + normalServerPort);
        assertThat(defaultClient.get("/home/foo").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(defaultClient.get("/home/hello").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(defaultClient.get("/home/api/v1/bar").aggregate().join().status())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(defaultClient.get("/home/deco").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void portBasedVirtualHost() {
        final WebClient fooClient = WebClient.builder("http://foo.com:" + fooHostPort)
                                             .factory(clientFactory)
                                             .build();

        assertThat(fooClient.get("/api/v1/bar").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(fooClient.get("/api/v1/good").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(fooClient.get("/api/v2/bad").aggregate().join().status())
                .isEqualTo(HttpStatus.NOT_FOUND);

        final WebClient barClient = WebClient.builder("http://bar.com:" + barHostPort)
                                             .factory(clientFactory)
                                             .build();

        assertThat(barClient.get("/api/v2/world").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(barClient.get("/api/v2/bad").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(barClient.get("/api/v2/deco").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(barClient.get("/api/v1/good").aggregate().join().status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void pathBasedVirtualHost() {
        final WebClient testClient = WebClient.builder("http://hostmap.com" + ":" + normalServerPort)
                                              .factory(clientFactory)
                                              .build();
        assertThat(testClient.get("/api/v3/me").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(testClient.get("/api/v3/you").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void baseContextPathWithScopedContextPath() {
        final BlockingWebClient client = BlockingWebClient.of(
                "http://127.0.0.1:" + normalServerPort);
        assertThat(client.get("/home/admin/foo")
                         .contentUtf8()).isEqualTo("/home/admin/foo");
        assertThat(client.get("/home/admin/foo")
                         .contentUtf8()).isEqualTo("/home/admin/foo");

        final BlockingWebClient fooClient = WebClient.builder("http://foo.com:" + fooHostPort)
                                                     .factory(clientFactory)
                                                     .build()
                                                     .blocking();
        assertThat(fooClient.get("/api/v1/admin/foo").contentUtf8())
                .isEqualTo("/api/v1/admin/foo");
    }
}

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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class PortBasedVirtualHostTest {

    private static int normalServerPort;
    private static int virtualHostPort;
    private static int fooHostPort;

    @RegisterExtension
    static ServerExtension serverWithPortMapping = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            try (ServerSocket ss = new ServerSocket(0)) {
                normalServerPort = ss.getLocalPort();
            }
            try (ServerSocket ss = new ServerSocket(0)) {
                virtualHostPort = ss.getLocalPort();
            }
            try (ServerSocket ss = new ServerSocket(0)) {
                fooHostPort = ss.getLocalPort();
            }

            sb.http(normalServerPort)
              .http(virtualHostPort)
              .http(fooHostPort)
              .service("/normal", (ctx, req) -> HttpResponse.of("normal"))
              .virtualHost(virtualHostPort)
              .service("/managed", (ctx, req) -> HttpResponse.of("managed"))
              .and()
              .virtualHost("foo.com:" + fooHostPort)
              .service("/foo", (ctx, req) -> HttpResponse.of("foo with port"))
              .and()
              .virtualHost("foo.com")
              .service("/foo-no-port", (ctx, req) -> HttpResponse.of("foo without port"))
              .and()
              .build();
        }
    };

    @Test
    void testNormalPort() {
        final WebClient client = WebClient.of("http://127.0.0.1:" + normalServerPort);
        AggregatedHttpResponse response = client.get("/normal").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("normal");

        response = client.get("/managed").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);

        response = client.get("/foo").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void testManagedPort() {
        final WebClient client = WebClient.of("http://127.0.0.1:" + virtualHostPort);
        AggregatedHttpResponse response = client.get("/normal").aggregate().join();
        // Fallback to default virtual host
        assertThat(response.contentUtf8()).isEqualTo("normal");

        // Served by the port-based virtual host. i.e, *:<port>.
        response = client.get("/managed").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("managed");

        // Should not serve services in other virtual hosts.
        response = client.get("/foo").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void testVirtualHostNameWithPort() {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .addressResolverGroupFactory(
                                                          unused -> MockAddressResolverGroup.localhost())
                                                  .build()) {

            final WebClient client = WebClient.builder("http://foo.com:" + fooHostPort)
                                              .factory(factory)
                                              .build();
            AggregatedHttpResponse response = client.get("/normal").aggregate().join();
            // Fallback to default virtual host
            assertThat(response.contentUtf8()).isEqualTo("normal");

            response = client.get("/managed").aggregate().join();
            assertThat(response.contentUtf8()).isEqualTo("managed");

            response = client.get("/foo").aggregate().join();
            assertThat(response.contentUtf8()).isEqualTo("foo with port");

            // Should not be served by "foo.com" virtual host
            response = client.get("/foo-no-port").aggregate().join();
            assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void testVirtualHostNameWithoutPort() {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .addressResolverGroupFactory(
                                                          unused -> MockAddressResolverGroup.localhost())
                                                  .build()) {

            final WebClient client = WebClient.builder("http://foo.com:" + normalServerPort)
                                              .factory(factory)
                                              .build();
            AggregatedHttpResponse response = client.get("/normal").aggregate().join();
            // Fallback to default virtual host
            assertThat(response.contentUtf8()).isEqualTo("normal");

            response = client.get("/managed").aggregate().join();
            assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);

            response = client.get("/foo").aggregate().join();
            assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);

            response = client.get("/foo-no-port").aggregate().join();
            assertThat(response.contentUtf8()).isEqualTo("foo without port");
        }
    }

    @Test
    void zeroVirtualHostPort() {
        assertThatThrownBy(() -> Server.builder().virtualHost(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port: 0 (expected: 1-65535)");
    }

    @Test
    void nonMatchingVirtualHostPort() {
        assertThatThrownBy(() -> {
            Server.builder()
                  .http(18080)
                  .virtualHost(18081)
                  .service("/", (ctx, req) -> HttpResponse.of("OK"))
                  .and()
                  .build();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("virtual host port: 18081 (expected: one of [18080])");

        assertThatThrownBy(() -> {
            Server.builder()
                  .http(18080)
                  .virtualHost("foo.com:18081")
                  .service("/", (ctx, req) -> HttpResponse.of("OK"))
                  .and()
                  .build();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("virtual host port: 18081 (expected: one of [18080])");
    }

    @Test
    void shouldReturnSameInstanceForDefaultPortBasedVirtualHost() {
        final ServerBuilder serverBuilder = Server.builder();
        final VirtualHostBuilder virtualHost1 = serverBuilder.virtualHost(18081);
        final VirtualHostBuilder virtualHost2 = serverBuilder.virtualHost(18081);
        assertThat(virtualHost1).isSameAs(virtualHost2);
        final VirtualHostBuilder virtualHost3 = serverBuilder.virtualHost(18082);
        assertThat(virtualHost2).isNotSameAs(virtualHost3);
    }
}

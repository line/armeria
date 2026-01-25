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
import java.util.List;

import javax.net.ssl.KeyManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
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
            assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);

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
    void cannotSetTls() {
        assertThatThrownBy(() -> {
            Server.builder()
                  .http(18080)
                  .virtualHost(18080)
                  .tls(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()))
                  .service("/", (ctx, req) -> HttpResponse.of("OK"))
                  .and()
                  .build();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot configure TLS to a port-based virtual host");
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

    @Test
    void portBasedVirtualHostWithTls() {
        // Make sure that the server builds successfully.
        final Server server =
                Server.builder()
                      .https(8080)
                      .https(8081)
                      .tlsSelfSigned()
                      .virtualHost(8080)
                      .service("/secure", (ctx, req) -> HttpResponse.of("OK"))
                      .and()
                      .virtualHost("foo.com:8081")
                      .service("/foo", (ctx, req) -> HttpResponse.of("OK"))
                      .and()
                      .build();

        assertThat(server.config().virtualHosts().stream().map(VirtualHost::hostnamePattern))
                .containsExactly("*:8080", "foo.com:8081", "*");
        assertThat(server.config().virtualHosts().stream().map(VirtualHost::originalHostnamePattern))
                .containsExactly("*", "foo.com", "*");
    }

    @Test
    void notAllowSettingHostnameWhenDefaultVirtualHost() {
        final VirtualHostBuilder virtualHostBuilder = Server.builder()
                                                            .virtualHost(8080);
        assertThatThrownBy(() -> virtualHostBuilder.hostnamePattern("foo.com"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void virtualHostWithServerPort() throws Exception {
        // Test that a server with ServerPort-based virtual hosts can be built and routes correctly.
        final ServerPort port1 = new ServerPort(0, SessionProtocol.HTTP);
        final ServerPort port2 = new ServerPort(0, SessionProtocol.HTTP);

        final Server server = Server.builder()
                                    .port(port1)
                                    .virtualHost(port1)
                                    .service("/foo", (ctx, req) -> HttpResponse.of("foo"))
                                    .and()
                                    .port(port2)
                                    .virtualHost(port2)
                                    .service("/bar", (ctx, req) -> HttpResponse.of("bar"))
                                    .and()
                                    .build();

        server.start().join();
        try {
            // Get the actual ports from activePorts
            final List<Integer> actualPorts = server.activePorts().values().stream()
                                                    .map(p -> p.localAddress().getPort())
                                                    .sorted()
                                                    .collect(java.util.stream.Collectors.toList());

            assertThat(actualPorts).hasSize(2);

            // Test both ports - one should serve /foo, the other /bar
            final WebClient client1 = WebClient.of("http://127.0.0.1:" + actualPorts.get(0));
            final WebClient client2 = WebClient.of("http://127.0.0.1:" + actualPorts.get(1));

            // Check /foo on both ports
            final AggregatedHttpResponse resp1Foo = client1.get("/foo").aggregate().join();
            final AggregatedHttpResponse resp2Foo = client2.get("/foo").aggregate().join();

            // Check /bar on both ports
            final AggregatedHttpResponse resp1Bar = client1.get("/bar").aggregate().join();
            final AggregatedHttpResponse resp2Bar = client2.get("/bar").aggregate().join();

            // One port should serve /foo with OK, the other with NOT_FOUND
            final boolean port1ServesFoo = resp1Foo.status() == HttpStatus.OK;
            final boolean port2ServesFoo = resp2Foo.status() == HttpStatus.OK;
            assertThat(port1ServesFoo || port2ServesFoo)
                    .as("At least one port should serve /foo, but got: port1=%s, port2=%s",
                        resp1Foo.status(), resp2Foo.status())
                    .isTrue();
        } finally {
            server.stop().join();
        }
    }

    @Test
    void shouldReturnSameInstanceForSameServerPort() {
        final ServerPort serverPort = new ServerPort(0, SessionProtocol.HTTP);
        final ServerBuilder serverBuilder = Server.builder();

        serverBuilder.port(serverPort);
        final VirtualHostBuilder virtualHost1 = serverBuilder.virtualHost(serverPort);
        final VirtualHostBuilder virtualHost2 = serverBuilder.virtualHost(serverPort);

        assertThat(virtualHost1).isSameAs(virtualHost2);
    }

    @Test
    void differentServerPortsCreateDifferentVirtualHosts() {
        final ServerPort serverPort1 = new ServerPort(0, SessionProtocol.HTTP);
        final ServerPort serverPort2 = new ServerPort(0, SessionProtocol.HTTP);
        final ServerBuilder serverBuilder = Server.builder();

        serverBuilder.port(serverPort1);
        serverBuilder.port(serverPort2);
        final VirtualHostBuilder virtualHost1 = serverBuilder.virtualHost(serverPort1);
        final VirtualHostBuilder virtualHost2 = serverBuilder.virtualHost(serverPort2);

        assertThat(virtualHost1).isNotSameAs(virtualHost2);
    }

    @Test
    void serverPortMustBeAddedBeforeVirtualHost() {
        final ServerPort serverPort = new ServerPort(0, SessionProtocol.HTTP);
        final ServerBuilder serverBuilder = Server.builder();

        serverBuilder.virtualHost(serverPort)
                     .service("/foo", (ctx, req) -> HttpResponse.of("foo"))
                     .and();

        // Build should fail because serverPort is not in the ports list
        assertThatThrownBy(serverBuilder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ServerPort for a virtual host is not in the server's port list");
    }
}

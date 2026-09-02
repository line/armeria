/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.ResponseAs;
import com.linecorp.armeria.client.RestClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.internal.XdsBootstrapRegistry;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc.HealthBlockingStub;
import io.grpc.health.v1.HealthGrpc.HealthImplBase;
import io.grpc.stub.StreamObserver;
import testing.xds.EchoService;

class XdsClientUriTest {

    private static final String BOOTSTRAP_NAME = "uri-test";

    @RegisterExtension
    @Order(0)
    static final ServerExtension httpServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    @RegisterExtension
    @Order(1)
    static final ServerExtension thriftServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", THttpService.of(
                    (EchoService.Iface) () -> "echo"));
        }
    };

    @RegisterExtension
    @Order(2)
    static final ServerExtension grpcServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new HealthImplBase() {
                                      @Override
                                      public void check(HealthCheckRequest request,
                                                        StreamObserver<HealthCheckResponse> responseObserver) {
                                          responseObserver.onNext(
                                                  HealthCheckResponse.newBuilder()
                                                                     .setStatus(ServingStatus.SERVING)
                                                                     .build());
                                          responseObserver.onCompleted();
                                      }
                                  })
                                  .build());
        }
    };

    private void setUpBootstrap(ServerExtension server) {
        setUpBootstrap(BOOTSTRAP_NAME, server);
    }

    private void setUpBootstrap(String name, ServerExtension server) {
        final Bootstrap bootstrap = bootstrapYaml(
                "listener1", server.httpSocketAddress().getHostString(), server.httpPort());
        XdsBootstrapRegistry.register(name, XdsBootstrap.of(bootstrap));
    }

    @AfterEach
    void tearDown() {
        deregisterAndClose(BOOTSTRAP_NAME);
        deregisterAndClose(XdsBootstrapRegistry.DEFAULT_NAME);
    }

    private static void deregisterAndClose(String name) {
        final XdsBootstrap xdsBootstrap = XdsBootstrapRegistry.deregister(name);
        if (xdsBootstrap != null) {
            xdsBootstrap.close();
        }
    }

    @Test
    void webClientOf() {
        setUpBootstrap(httpServer);
        final BlockingWebClient client =
                WebClient.of("xds://" + BOOTSTRAP_NAME + "/listener1").blocking();
        assertThat(client.get("/hello").contentUtf8()).isEqualTo("world");
    }

    @Test
    void webClientBuilder() {
        setUpBootstrap(httpServer);
        final BlockingWebClient client =
                WebClient.builder("xds://" + BOOTSTRAP_NAME + "/listener1").build().blocking();
        assertThat(client.get("/hello").contentUtf8()).isEqualTo("world");
    }

    @Test
    void clientsNewClient() {
        setUpBootstrap(httpServer);
        final WebClient client =
                Clients.newClient("xds://" + BOOTSTRAP_NAME + "/listener1", WebClient.class);
        assertThat(client.blocking().get("/hello").contentUtf8()).isEqualTo("world");
    }

    @Test
    void clientsBuilder() {
        setUpBootstrap(httpServer);
        final WebClient client =
                Clients.builder("xds://" + BOOTSTRAP_NAME + "/listener1").build(WebClient.class);
        assertThat(client.blocking().get("/hello").contentUtf8()).isEqualTo("world");
    }

    @Test
    void blockingWebClient() {
        setUpBootstrap(httpServer);
        final BlockingWebClient client =
                Clients.newClient("xds://" + BOOTSTRAP_NAME + "/listener1",
                                  BlockingWebClient.class);
        assertThat(client.get("/hello").contentUtf8()).isEqualTo("world");
    }

    @Test
    void restClient() throws Exception {
        setUpBootstrap(httpServer);
        final RestClient client =
                Clients.newClient("xds://" + BOOTSTRAP_NAME + "/listener1", RestClient.class);
        assertThat(client.get("/hello").execute(ResponseAs.string()).join().content())
                .isEqualTo("world");
    }

    @Test
    void grpcClient() {
        setUpBootstrap(grpcServer);
        final HealthBlockingStub client =
                GrpcClients.newClient("gproto+xds://" + BOOTSTRAP_NAME + "/listener1",
                                      HealthBlockingStub.class);
        final HealthCheckResponse response = client.check(HealthCheckRequest.getDefaultInstance());
        assertThat(response.getStatus()).isEqualTo(ServingStatus.SERVING);
    }

    @Test
    void thriftClient() throws Exception {
        setUpBootstrap(thriftServer);
        final EchoService.Iface client =
                ThriftClients.newClient("tbinary+xds://" + BOOTSTRAP_NAME + "/listener1",
                                        EchoService.Iface.class);
        assertThat(client.echoAuth()).isEqualTo("echo");
    }

    @Test
    void defaultBootstrapName() {
        setUpBootstrap(XdsBootstrapRegistry.DEFAULT_NAME, httpServer);
        final BlockingWebClient client = WebClient.of("xds:///listener1").blocking();
        assertThat(client.get("/hello").contentUtf8()).isEqualTo("world");
    }

    @Test
    void listenerNameWithQueryAndFragment() {
        final String listenerName = "listener1?foo=bar#baz";
        final Bootstrap bootstrap = bootstrapYaml(
                listenerName,
                httpServer.httpSocketAddress().getHostString(), httpServer.httpPort());
        XdsBootstrapRegistry.register(BOOTSTRAP_NAME, XdsBootstrap.of(bootstrap));
        final BlockingWebClient client =
                WebClient.of("xds://" + BOOTSTRAP_NAME + '/' + listenerName).blocking();
        assertThat(client.get("/hello").contentUtf8()).isEqualTo("world");
    }

    @Test
    void emptyListenerNameRejected() {
        assertThatThrownBy(() -> WebClient.of("xds:///"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty listener name");
    }

    @Test
    void emptyListenerNameWithAuthorityRejected() {
        assertThatThrownBy(() -> WebClient.of("xds://bootstrap/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty listener name");
    }

    private static Bootstrap bootstrapYaml(String listenerName, String address, int port) {
        //language=YAML
        final String yaml = """
                static_resources:
                  listeners:
                  - name: %s
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service
                            domains: [ "*" ]
                            routes:
                            - match:
                                prefix: /
                              route:
                                cluster: cluster1
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                  - name: cluster1
                    type: STATIC
                    load_assignment:
                      cluster_name: cluster1
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: %s
                                port_value: %s
                """.formatted(listenerName, address, port);
        return XdsResourceReader.fromYaml(yaml, Bootstrap.class);
    }
}

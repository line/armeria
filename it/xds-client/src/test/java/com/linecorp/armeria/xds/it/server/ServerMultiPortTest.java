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

package com.linecorp.armeria.xds.it.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.it.XdsCertificateExtension;
import com.linecorp.armeria.xds.it.XdsControlPlaneExtension;
import com.linecorp.armeria.xds.it.XdsResourceReader;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.listener.v3.Listener;

class ServerMultiPortTest {

    private static final String LISTENER_NAME = "multi-port-listener";

    @RegisterExtension
    @Order(0)
    static final XdsCertificateExtension cert =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(1)
    static final XdsControlPlaneExtension controlPlane = new XdsControlPlaneExtension();

    @RegisterExtension
    @Order(2)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final Path certPath = cert.certificateFile().toPath();
            final Path keyPath = cert.privateKeyFile().toPath();

            //language=YAML
            final String yaml =
                    """
                    name: %s
                    default_filter_chain:
                      filters:
                        - name: envoy.filters.network.http_connection_manager
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters\
                    .network.http_connection_manager.v3.HttpConnectionManager
                            stat_prefix: ingress_http
                            route_config:
                              name: local_route
                              virtual_hosts:
                                - name: local_service
                                  domains: ["*"]
                                  routes:
                                    - match:
                                        prefix: "/"
                                      non_forwarding_action: {}
                            http_filters:
                              - name: envoy.filters.http.router
                      transport_socket:
                        name: envoy.transport_sockets.downstream_tls
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                    .tls.v3.DownstreamTlsContext
                          common_tls_context:
                            tls_certificates:
                              - certificate_chain:
                                  filename: "%s"
                                private_key:
                                  filename: "%s"
                    """.formatted(LISTENER_NAME, certPath, keyPath);
            controlPlane.set(XdsResourceReader.fromYaml(yaml, Listener.class));
            sb.plugin(XdsServerPlugin.of(controlPlane.bootstrap(), LISTENER_NAME, 0, 0));
            sb.service("/hello", (ctx, req) -> HttpResponse.of("hello"));
        }
    };

    @Test
    void multiplePortsSinglePlugin() {
        final List<Integer> httpsPorts = server.server().activePorts().values().stream()
                                              .filter(ServerPort::hasHttps)
                                              .map(p -> p.localAddress().getPort())
                                              .collect(Collectors.toList());
        assertThat(httpsPorts).hasSize(2);

        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(cert.certificate())
                                                   .build();
        for (int port : httpsPorts) {
            final BlockingWebClient client =
                    WebClient.of("https://127.0.0.1:" + port).blocking();
            final AggregatedHttpResponse res = client.execute(
                    HttpRequest.of(HttpMethod.GET, "/hello"),
                    RequestOptions.builder().clientTlsSpec(tlsSpec).build());
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentUtf8()).isEqualTo("hello");
        }
    }
}

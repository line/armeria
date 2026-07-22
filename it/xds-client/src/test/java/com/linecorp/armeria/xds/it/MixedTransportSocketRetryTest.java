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

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class MixedTransportSocketRetryTest {

    @RegisterExtension
    @Order(0)
    static final XdsCertificateExtension cert =
            new XdsCertificateExtension(new SelfSignedCertificateExtension());

    @RegisterExtension
    @Order(1)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tls(cert.tlsKeyPair());
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE));
        }
    };

    // language=YAML
    private static final String BOOTSTRAP_TEMPLATE =
            """
            static_resources:
              listeners:
              - name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.\
            http_connection_manager.v3.HttpConnectionManager
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
                              cluster: my-cluster
                              retry_policy:
                                retry_on: "5xx"
                                num_retries: 3
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.\
            http.router.v3.Router
              clusters:
              - name: my-cluster
                type: STATIC
                lb_policy: ROUND_ROBIN
                load_assignment:
                  cluster_name: my-cluster
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: %d
                      metadata:
                        filter_metadata:
                          "envoy.transport_socket_match":
                            mode: "tls"
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: %d
                      metadata:
                        filter_metadata:
                          "envoy.transport_socket_match":
                            mode: "plaintext"
                transport_socket:
                  name: envoy.transport_sockets.tls
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.transport_sockets.\
            tls.v3.UpstreamTlsContext
                    common_tls_context:
                      validation_context:
                        trusted_ca:
                          inline_bytes: %s
                transport_socket_matches:
                - name: tls-match
                  match:
                    mode: "tls"
                  transport_socket:
                    name: envoy.transport_sockets.tls
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.transport_sockets.\
            tls.v3.UpstreamTlsContext
                      common_tls_context:
                        validation_context:
                          trusted_ca:
                            inline_bytes: %s
                - name: plaintext-match
                  match:
                    mode: "plaintext"
                  transport_socket:
                    name: envoy.transport_sockets.raw_buffer
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.transport_sockets.\
            raw_buffer.v3.RawBuffer
            """;

    @Test
    void retryChildContextsHaveCorrectSessionProtocol() throws Exception {
        final String ca = base64Cert(cert.certificateFile());
        final String yaml = BOOTSTRAP_TEMPLATE.formatted(
                server.httpsPort(), server.httpPort(), ca, ca);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(yaml));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {

            final WebClient client = WebClient.of(preprocessor);

            // Send 2 requests so round robin hits both endpoints across attempts.
            for (int i = 0; i < 2; i++) {
                final ClientRequestContext parentCtx;
                try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                    final AggregatedHttpResponse res =
                            client.blocking().execute(HttpRequest.of(HttpMethod.GET, "/"));
                    assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    parentCtx = captor.get();
                }

                // 1 original + 3 retries = 4 child contexts
                final List<RequestLogAccess> children = parentCtx.log().children();
                assertThat(children).hasSize(4);

                for (RequestLogAccess childLogAccess : children) {
                    final RequestLog childLog = childLogAccess.whenComplete().join();
                    final SessionProtocol protocol = childLog.sessionProtocol();
                    final ClientRequestContext childCtx =
                            (ClientRequestContext) childLogAccess.context();
                    final int port = childCtx.endpoint().port();
                    if (port == server.httpsPort()) {
                        assertThat(protocol.isTls()).isTrue();
                    } else {
                        assertThat(protocol.isTls()).isFalse();
                    }
                }
            }
        }
    }

    private static String base64Cert(File certFile) throws Exception {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(certFile.toPath()));
    }
}

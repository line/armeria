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

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class TransportSocketMatchesIntegrationTest {

    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension("localhost");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(serverCert.certificateFile(), serverCert.privateKeyFile());
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @RegisterExtension
    static final SelfSignedCertificateExtension otherCert = new SelfSignedCertificateExtension("localhost");

    // language=YAML
    //language=YAML
    private static final String bootstrapTemplate =
            """
            static_resources:
              listeners:
              - name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
            .v3.HttpConnectionManager
                    stat_prefix: http
                    route_config:
                      name: local_route
                      virtual_hosts:
                      - name: local_service1
                        domains: [ "*" ]
                        routes:
                          - match:
                              prefix: /
                            route:
                              cluster: my-cluster
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
              clusters:
              - name: my-cluster
                type: STATIC
                load_assignment:
                  cluster_name: my-cluster
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: %s
                      metadata:
                        filter_metadata:
                          "envoy.transport_socket_match":
                            env: "%s"
                transport_socket:
                  name: envoy.transport_sockets.tls
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.transport_sockets\
            .tls.v3.UpstreamTlsContext
                    common_tls_context:
                      validation_context:
                        trusted_ca:
                          inline_bytes: %s
                transport_socket_matches:
                - name: prod
                  match:
                    env: "prod"
                  transport_socket:
                    name: envoy.transport_sockets.tls
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.transport_sockets\
            .tls.v3.UpstreamTlsContext
                      common_tls_context:
                        validation_context:
                          trusted_ca:
                            inline_bytes: %s
            """;

    @Test
    void requestSucceedsWhenMatchOverridesDefault() throws Exception {
        final String defaultCa = base64Cert(otherCert.certificateFile());
        final String matchCa = base64Cert(serverCert.certificateFile());
        final String bootstrap = bootstrap(server.httpsPort(), "prod", defaultCa, matchCa);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res = fetch(preprocessor);
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void requestFailsWhenNoMatchUsesDefault() throws Exception {
        final String defaultCa = base64Cert(otherCert.certificateFile());
        final String matchCa = base64Cert(serverCert.certificateFile());
        final String bootstrap = bootstrap(server.httpsPort(), "staging", defaultCa, matchCa);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            assertThatThrownBy(() -> fetch(preprocessor))
                    .isInstanceOf(UnprocessedRequestException.class);
        }
    }

    private static String bootstrap(int port, String endpointEnv, String defaultCa, String matchCa) {
        return bootstrapTemplate.formatted(port, endpointEnv, defaultCa, matchCa);
    }

    private static String base64Cert(File certFile) throws Exception {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(certFile.toPath()));
    }

    private static AggregatedHttpResponse fetch(XdsHttpPreprocessor preprocessor) {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .connectTimeoutMillis(1000)
                                  .build()) {
            return WebClient.builder(preprocessor)
                            .factory(clientFactory)
                            .responseTimeoutMillis(3000)
                            .build()
                            .blocking()
                            .get("/");
        }
    }
}

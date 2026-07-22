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

package com.linecorp.armeria.xds.it.athenz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.google.common.collect.ImmutableList;
import com.yahoo.athenz.zms.Assertion;
import com.yahoo.athenz.zms.AssertionEffect;
import com.yahoo.athenz.zms.Policy;
import com.yahoo.athenz.zms.ZMSClient;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.AthenzTokenClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.athenz.AthenzDocker;
import com.linecorp.armeria.server.athenz.AthenzExtension;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.it.XdsCertificateExtension;
import com.linecorp.armeria.xds.it.XdsResourceReader;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

@EnabledIfDockerAvailable
class AthenzAccessTokenConstraintFilterTest {

    private static final String LISTENER_NAME = "server-listener";
    private static final String POLICY_NAME = "constraint-policy";
    private static final String ATHENZ_RESOURCES = "gen-src/test/resources";

    private static final ServerPort xdsPort =
            new ServerPort(0, SessionProtocol.HTTP, SessionProtocol.HTTPS);

    @RegisterExtension
    @Order(0)
    static final AthenzExtension athenz =
            new AthenzExtension(new File("gen-src/test/resources/docker/docker-compose.yml")) {
                @Override
                protected void scaffold(ZMSClient zmsClient) {
                    // Literal mapping: action=obtain, resource=testing:literal
                    final Assertion literalAssertion =
                            newAssertion("obtain", AthenzDocker.TEST_DOMAIN_NAME + ":literal");
                    // Default mapping: action=get (lower of GET), resource=testing:/default (the path)
                    final Assertion defaultAssertion =
                            newAssertion("get", AthenzDocker.TEST_DOMAIN_NAME + ":/default");
                    // Template mapping: action=obtain, resource=testing:template
                    final Assertion templateAssertion =
                            newAssertion("obtain", AthenzDocker.TEST_DOMAIN_NAME + ":template");
                    // No-prefix mapping: action=obtain, resource=testing:no_prefix
                    // The filter config uses just "no_prefix" (without the domain: prefix)
                    final Assertion noPrefixAssertion =
                            newAssertion("obtain", AthenzDocker.TEST_DOMAIN_NAME + ":no_prefix");

                    final Policy policy = new Policy();
                    policy.setName(AthenzDocker.TEST_DOMAIN_NAME + ":policy." + POLICY_NAME);
                    policy.setAssertions(ImmutableList.of(literalAssertion, defaultAssertion,
                                                          templateAssertion, noPrefixAssertion));
                    zmsClient.putPolicy(AthenzDocker.TEST_DOMAIN_NAME, POLICY_NAME,
                                        "create-policy-audit-ref", policy);
                }

                private static Assertion newAssertion(String action, String resource) {
                    final Assertion assertion = new Assertion();
                    assertion.setRole(AthenzDocker.TEST_DOMAIN_NAME + ":role." + AthenzDocker.USER_ROLE);
                    assertion.setAction(action);
                    assertion.setResource(resource);
                    assertion.setEffect(AssertionEffect.ALLOW);
                    return assertion;
                }
            };

    @RegisterExtension
    @Order(1)
    static final XdsCertificateExtension serverCert =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(2)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final Bootstrap bootstrap =
                    XdsResourceReader.fromYaml(bootstrapYaml(), Bootstrap.class);
            final XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
            sb.plugin(XdsServerPlugin.builder(xdsBootstrap, LISTENER_NAME)
                                     .port(xdsPort).build());
            sb.service("/literal", (ctx, req) -> HttpResponse.of("ok"));
            sb.service("/default", (ctx, req) -> HttpResponse.of("ok"));
            sb.service("/template", (ctx, req) -> HttpResponse.of("ok"));
            sb.service("/no-prefix", (ctx, req) -> HttpResponse.of("ok"));
        }
    };

    private static String bootstrapYaml() {
        final URI ztsUri = athenz.ztsUri();
        final Path certPath = serverCert.certificateFile().toPath();
        final Path keyPath = serverCert.privateKeyFile().toPath();
        final String serviceCertFile = ATHENZ_RESOURCES + AthenzDocker.ATHENZ_CERTS +
                                       AthenzDocker.TEST_SERVICE + "/cert.pem";
        final String serviceKeyFile = ATHENZ_RESOURCES + AthenzDocker.ATHENZ_CERTS +
                                      AthenzDocker.TEST_SERVICE + "/key.pem";
        final String caCertFile = ATHENZ_RESOURCES + AthenzDocker.CA_CERT_FILE;

        final String domain = AthenzDocker.TEST_DOMAIN_NAME;

        //language=YAML
        return """
                static_resources:
                  listeners:
                    - name: %s
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
                                          prefix: "/literal"
                                        non_forwarding_action: {}
                                        typed_per_filter_config:
                                          athenz.access_token_constraint:
                                            "@type": type.googleapis.com/armeria.xds\
                .athenz.AccessTokenConstraintConfig
                                            zts_cluster_name: zts-cluster
                                            access_token_constraint:
                                              constraint_domain: %s
                                              syntax_version: 1
                                              assertion_mapping:
                                                rules:
                                                - action:
                                                    literal: obtain
                                                  resource:
                                                    literal: "%s:literal"
                                      - match:
                                          prefix: "/template"
                                        non_forwarding_action: {}
                                        typed_per_filter_config:
                                          athenz.access_token_constraint:
                                            "@type": type.googleapis.com/armeria.xds\
                .athenz.AccessTokenConstraintConfig
                                            zts_cluster_name: zts-cluster
                                            access_token_constraint:
                                              constraint_domain: %s
                                              syntax_version: 1
                                              assertion_mapping:
                                                rules:
                                                - conditions:
                                                  - attribute:
                                                      well_known: WELL_KNOWN_ENDPOINT_ATTRIBUTE_METHOD
                                                    matcher:
                                                      exact: GET
                                                  - attribute:
                                                      well_known: WELL_KNOWN_ENDPOINT_ATTRIBUTE_PATH
                                                    name: pathCapture
                                                    matcher:
                                                      safe_regex:
                                                        regex: "/(.+)"
                                                  action:
                                                    literal: obtain
                                                  resource:
                                                    template:
                                                      template: "%s:${match.pathCapture.1}"
                                      - match:
                                          prefix: "/no-prefix"
                                        non_forwarding_action: {}
                                        typed_per_filter_config:
                                          athenz.access_token_constraint:
                                            "@type": type.googleapis.com/armeria.xds\
                .athenz.AccessTokenConstraintConfig
                                            zts_cluster_name: zts-cluster
                                            access_token_constraint:
                                              constraint_domain: %s
                                              syntax_version: 1
                                              assertion_mapping:
                                                rules:
                                                - action:
                                                    literal: obtain
                                                  resource:
                                                    literal: no_prefix
                                      - match:
                                          prefix: "/"
                                        non_forwarding_action: {}
                              http_filters:
                                - name: athenz.access_token_constraint
                                  typed_config:
                                    "@type": type.googleapis.com/armeria.xds\
                .athenz.AccessTokenConstraintConfig
                                    zts_cluster_name: zts-cluster
                                    access_token_constraint:
                                      constraint_domain: %s
                                      syntax_version: 1
                                - name: envoy.filters.http.router
                        transport_socket:
                          name: envoy.transport_sockets.downstream_tls
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.DownstreamTlsContext
                            common_tls_context:
                              tls_certificates:
                                - certificate_chain:
                                    filename: '%s'
                                  private_key:
                                    filename: '%s'
                  clusters:
                    - name: zts-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: zts-cluster
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: %s
                                      port_value: %d
                      transport_socket:
                        name: envoy.transport_sockets.tls
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions\
                .transport_sockets.tls.v3.UpstreamTlsContext
                          common_tls_context:
                            tls_certificates:
                              - certificate_chain:
                                  filename: '%s'
                                private_key:
                                  filename: '%s'
                            validation_context:
                              trusted_ca:
                                filename: '%s'
                """.formatted(
                LISTENER_NAME,
                domain, domain,             // literal per-route config
                domain, domain,             // template per-route config
                domain,                     // no-prefix per-route config
                domain,                     // filter-level config
                certPath, keyPath,          // DownstreamTlsContext certs
                ztsUri.getHost(), ztsUri.getPort(),
                serviceCertFile, serviceKeyFile, caCertFile);
    }

    @Test
    void noTokenReturnsUnauthorized() {
        final BlockingWebClient client = xdsClient();
        final RequestOptions tlsOptions = tlsOptions();
        await().untilAsserted(() -> {
            final AggregatedHttpResponse response = client.execute(
                    HttpRequest.of(HttpMethod.GET, "/literal"), tlsOptions);
            assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        });
    }

    @Test
    void validTokenWithLiteralMapping() {
        final String token = obtainAccessToken();
        final BlockingWebClient client = xdsClient();
        final RequestOptions tlsOptions = tlsOptions();
        await().untilAsserted(() -> {
            final AggregatedHttpResponse response = client.execute(
                    HttpRequest.builder()
                               .get("/literal")
                               .header("authorization", "Bearer " + token)
                               .build(),
                    tlsOptions);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("ok");
        });
    }

    @Test
    void defaultMappingUsesMethodAndPath() {
        final String token = obtainAccessToken();
        final BlockingWebClient client = xdsClient();
        final RequestOptions tlsOptions = tlsOptions();
        await().untilAsserted(() -> {
            final AggregatedHttpResponse response = client.execute(
                    HttpRequest.builder()
                               .get("/default")
                               .header("authorization", "Bearer " + token)
                               .build(),
                    tlsOptions);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("ok");
        });
    }

    @Test
    void literalMappingWithoutDomainPrefix() {
        final String token = obtainAccessToken();
        final BlockingWebClient client = xdsClient();
        final RequestOptions tlsOptions = tlsOptions();
        await().untilAsserted(() -> {
            final AggregatedHttpResponse response = client.execute(
                    HttpRequest.builder()
                               .get("/no-prefix")
                               .header("authorization", "Bearer " + token)
                               .build(),
                    tlsOptions);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("ok");
        });
    }

    @Test
    void validTokenWithTemplateMapping() {
        final String token = obtainAccessToken();
        final BlockingWebClient client = xdsClient();
        final RequestOptions tlsOptions = tlsOptions();
        await().untilAsserted(() -> {
            final AggregatedHttpResponse response = client.execute(
                    HttpRequest.builder()
                               .get("/template")
                               .header("authorization", "Bearer " + token)
                               .build(),
                    tlsOptions);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("ok");
        });
    }

    private static RequestOptions tlsOptions() {
        final ClientTlsSpec clientTlsSpec =
                ClientTlsSpec.builder()
                             .trustedCertificates(serverCert.certificate())
                             .build();
        return RequestOptions.builder().clientTlsSpec(clientTlsSpec).build();
    }

    private static BlockingWebClient xdsClient() {
        return WebClient.of("https://127.0.0.1:" + xdsPort.actualPort()).blocking();
    }

    private static String obtainAccessToken() {
        final AthenzTokenClient tokenClient =
                AthenzTokenClient.builder(athenz.newZtsBaseClient(AthenzDocker.TEST_SERVICE))
                                 .domainName(AthenzDocker.TEST_DOMAIN_NAME)
                                 .roleNames(ImmutableList.of(AthenzDocker.USER_ROLE))
                                 .build();
        return tokenClient.getToken().join();
    }
}

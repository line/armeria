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

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;
import com.linecorp.armeria.xds.it.XdsCertificateExtension;
import com.linecorp.armeria.xds.it.XdsControlPlaneExtension;
import com.linecorp.armeria.xds.it.XdsResourceReader;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

class ServerDecoratorTest {

    private static final String LISTENER_NAME = "decorator-listener";

    @RegisterExtension
    @Order(0)
    static final XdsCertificateExtension cert1 =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(1)
    static final XdsCertificateExtension cert2 =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(2)
    static final XdsCertificateExtension cert3 =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(3)
    static final XdsCertificateExtension cert4 =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(4)
    static final XdsControlPlaneExtension controlPlane =
            new XdsControlPlaneExtension(b -> b.extensionFactories(new TestHeaderFilter()));

    @RegisterExtension
    @Order(5)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            controlPlane.set(Listener.newBuilder().setName(LISTENER_NAME).build());
            sb.plugin(XdsServerPlugin.of(controlPlane.bootstrap(), LISTENER_NAME));
            sb.service("/hello", (ctx, req) -> HttpResponse.of("hello"));
            sb.service("/route-a", (ctx, req) -> HttpResponse.of("route-a"));
            sb.service("/route-b", (ctx, req) -> HttpResponse.of("route-b"));
        }
    };

    @Test
    void xdsDecoratorAddsResponseHeader() {
        final Path certPath = cert1.certificateFile().toPath();
        final Path keyPath = cert1.privateKeyFile().toPath();

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
                          - name: test.header_filter
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
                """.formatted(LISTENER_NAME, certPath, keyPath);
        final String ver = controlPlane.set(XdsResourceReader.fromYaml(yaml, Listener.class));
        controlPlane.awaitListener(LISTENER_NAME, ver);

        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(cert1.certificate())
                                                   .build();
        final BlockingWebClient client = WebClient.of(server.httpsUri()).blocking();
        final AggregatedHttpResponse res = client.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build());

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello");
        assertThat(res.headers().get("x-xds-decorator")).isEqualTo("applied");
    }

    @Test
    void noMatchingRoute() {
        final Path certPath = cert2.certificateFile().toPath();
        final Path keyPath = cert2.privateKeyFile().toPath();

        // Route only matches "/other" — request to "/hello" should get 404.
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
                                    prefix: "/other"
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
                              filename: '%s'
                            private_key:
                              filename: '%s'
                """.formatted(LISTENER_NAME, certPath, keyPath);
        final String ver = controlPlane.set(XdsResourceReader.fromYaml(yaml, Listener.class));
        controlPlane.awaitListener(LISTENER_NAME, ver);

        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(cert2.certificate())
                                                   .build();
        final BlockingWebClient client = WebClient.of(server.httpsUri()).blocking();
        final AggregatedHttpResponse res = client.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build());
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void noRouterFilter() {
        final Path certPath = cert3.certificateFile().toPath();
        final Path keyPath = cert3.privateKeyFile().toPath();

        // HCM with routes but no envoy.filters.http.router → 503.
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
                          - name: test.header_filter
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
                """.formatted(LISTENER_NAME, certPath, keyPath);
        final String ver = controlPlane.set(XdsResourceReader.fromYaml(yaml, Listener.class));
        controlPlane.awaitListener(LISTENER_NAME, ver);

        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(cert3.certificate())
                                                   .build();
        final BlockingWebClient client = WebClient.of(server.httpsUri()).blocking();
        final AggregatedHttpResponse res = client.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build());
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void perRouteFilterConfig() {
        final Path certPath = cert4.certificateFile().toPath();
        final Path keyPath = cert4.privateKeyFile().toPath();

        // Two routes with different typed_per_filter_config for test.header_filter.
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
                                    prefix: "/route-a"
                                  non_forwarding_action: {}
                                  typed_per_filter_config:
                                    test.header_filter:
                                      "@type": type.googleapis.com/google.protobuf.StringValue
                                      value: "from-route-a"
                                - match:
                                    prefix: "/route-b"
                                  non_forwarding_action: {}
                                  typed_per_filter_config:
                                    test.header_filter:
                                      "@type": type.googleapis.com/google.protobuf.StringValue
                                      value: "from-route-b"
                        http_filters:
                          - name: test.header_filter
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
                """.formatted(LISTENER_NAME, certPath, keyPath);
        final String ver = controlPlane.set(XdsResourceReader.fromYaml(yaml, Listener.class));
        controlPlane.awaitListener(LISTENER_NAME, ver);

        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(cert4.certificate())
                                                   .build();
        final BlockingWebClient client = WebClient.of(server.httpsUri()).blocking();

        // Route A should have header value "from-route-a".
        final AggregatedHttpResponse resA = client.execute(
                HttpRequest.of(HttpMethod.GET, "/route-a"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build());
        assertThat(resA.status()).isEqualTo(HttpStatus.OK);
        assertThat(resA.contentUtf8()).isEqualTo("route-a");
        assertThat(resA.headers().get("x-xds-decorator")).isEqualTo("from-route-a");

        // Route B should have header value "from-route-b".
        final AggregatedHttpResponse resB = client.execute(
                HttpRequest.of(HttpMethod.GET, "/route-b"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build());
        assertThat(resB.status()).isEqualTo(HttpStatus.OK);
        assertThat(resB.contentUtf8()).isEqualTo("route-b");
        assertThat(resB.headers().get("x-xds-decorator")).isEqualTo("from-route-b");
    }

    private static final class TestHeaderFilter implements HttpFilterFactory {

        @Override
        public String name() {
            return "test.header_filter";
        }

        @Override
        public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
            final String headerValue;
            if (config.getTypeUrl().contains("google.protobuf.StringValue")) {
                headerValue = context.validator().unpack(config, StringValue.class).getValue();
            } else {
                headerValue = "applied";
            }
            return new XdsHttpFilter() {
                @Override
                public DecoratingHttpServiceFunction serviceDecorator() {
                    return (delegate, ctx, req) ->
                            delegate.serve(ctx, req)
                                    .mapHeaders(headers -> headers.toBuilder()
                                                                  .add(HttpHeaderNames.of("x-xds-decorator"),
                                                                       headerValue)
                                                                  .build());
                }
            };
        }
    }
}

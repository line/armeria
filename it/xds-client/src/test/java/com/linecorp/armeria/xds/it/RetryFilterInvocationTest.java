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

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;

import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.client.endpoint.XdsRpcPreprocessor;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import testing.xds.EchoService;

class RetryFilterInvocationTest {

    @Test
    void downstreamOnceUpstreamPerAttempt() {
        final AtomicInteger downstreamCount = new AtomicInteger();
        final AtomicInteger upstreamCount = new AtomicInteger();
        final HttpFilterFactory downstreamFactory =
                countingFilterFactory("test.downstream", downstreamCount);
        final HttpFilterFactory upstreamFactory =
                countingFilterFactory("test.upstream", upstreamCount);

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                bootstrapYaml("5xx"), Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .extensionFactories(downstreamFactory, upstreamFactory)
                                                     .build();
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse res =
                        WebClient.builder(preprocessor)
                                 .decorator((delegate, ctx0, req) ->
                                                    HttpResponse.of(ResponseHeaders.of(503)))
                                 .build()
                                 .blocking()
                                 .execute(HttpRequest.of(HttpMethod.GET, "/"));
                assertThat(res.status().code()).isEqualTo(503);
                ctx = captor.get();
            }
            // 1 original + 2 retries = 3 total attempts
            assertThat(ctx.log().children()).hasSize(3);

            // Downstream wraps the entire retry loop → invoked once
            assertThat(downstreamCount.get()).isEqualTo(1);
            // Upstream runs inside each attempt → invoked 3 times
            assertThat(upstreamCount.get()).isEqualTo(3);
        }
    }

    @Test
    void rpcDownstreamOnceUpstreamPerAttempt() {
        final AtomicInteger downstreamCount = new AtomicInteger();
        final AtomicInteger upstreamCount = new AtomicInteger();
        final HttpFilterFactory downstreamFactory =
                countingFilterFactory("test.downstream", downstreamCount);
        final HttpFilterFactory upstreamFactory =
                countingFilterFactory("test.upstream", upstreamCount);

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                bootstrapYaml("reset"), Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .extensionFactories(downstreamFactory, upstreamFactory)
                                                     .build();
             XdsRpcPreprocessor preprocessor =
                     XdsRpcPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final EchoService.Iface client =
                        ThriftClients.builder(preprocessor)
                                     .rpcDecorator((delegate, ctx0, req) ->
                                                           RpcResponse.ofFailure(
                                                                   new TException("always fail")))
                                     .build(EchoService.Iface.class);
                assertThatThrownBy(client::echoAuth).isInstanceOf(TException.class);
                ctx = captor.get();
            }
            // 1 original + 2 retries = 3 total attempts
            assertThat(ctx.log().children()).hasSize(3);

            // Downstream wraps the entire retry loop → invoked once
            assertThat(downstreamCount.get()).isEqualTo(1);
            // Upstream runs inside each attempt → invoked 3 times
            assertThat(upstreamCount.get()).isEqualTo(3);
        }
    }

    //language=YAML
    private static String bootstrapYaml(String retryOn) {
        return """
                static_resources:
                  listeners:
                  - name: test-listener
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
                                cluster: test-cluster
                                retry_policy:
                                  retry_on: "%s"
                                  num_retries: 2
                        http_filters:
                        - name: test.downstream
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                            upstream_http_filters:
                            - name: test.upstream
                  clusters:
                  - name: test-cluster
                    type: STATIC
                    load_assignment:
                      cluster_name: test-cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8080
                """.formatted(retryOn);
    }

    private static HttpFilterFactory countingFilterFactory(String name, AtomicInteger counter) {
        return new HttpFilterFactory() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
                return new XdsHttpFilter() {
                    @Override
                    public DecoratingHttpClientFunction httpDecorator() {
                        return (delegate, ctx, req) -> {
                            counter.incrementAndGet();
                            return delegate.execute(ctx, req);
                        };
                    }

                    @Override
                    public DecoratingRpcClientFunction rpcDecorator() {
                        return (delegate, ctx, req) -> {
                            counter.incrementAndGet();
                            return delegate.execute(ctx, req);
                        };
                    }
                };
            }
        };
    }
}

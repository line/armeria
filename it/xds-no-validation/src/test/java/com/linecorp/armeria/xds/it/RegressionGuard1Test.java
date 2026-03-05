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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsEndpointGroup;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

class RegressionGuard1Test {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final AtomicLong version = new AtomicLong();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .build());
            sb.service("/internal/health", HealthCheckService.of());
            sb.service("/hello", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @SuppressWarnings("checkstyle:LineLength")
    //language=JSON
    private static final String listenerJson =
            """
            {
              "name": "my-listener",
              "apiListener": {
                "apiListener": {
                  "@type": "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager",
                  "statPrefix": "ingress_http",
                  "rds": {
                    "configSource": {
                      "ads": {},
                      "resourceApiVersion": "V3"
                    },
                    "routeConfigName": "my-route"
                  }
                }
              }
            }
            """;

    //language=JSON
    private static final String routeJson =
            """
            {
              "name": "my-route",
              "virtualHosts": [
                {
                  "domains": [
                    "*"
                  ],
                  "routes": [
                    {
                      "match": {
                        "prefix": "/"
                      },
                      "route": {
                        "cluster": "my-cluster"
                      }
                    }
                  ]
                }
              ]
            }
            """;

    //language=JSON
    private static final String clusterJson =
            """
            {
              "name": "my-cluster",
              "type": "EDS",
              "edsClusterConfig": {
                "edsConfig": {
                  "ads": {},
                  "resourceApiVersion": "V3"
                },
                "serviceName": "my-cluster"
              },
              "healthChecks": [
                {
                  "httpHealthCheck": {
                    "host": "127.0.0.1:%s",
                    "path": "/internal/health"
                  }
                }
              ],
              "transportSocket": {
                "name": "envoy.transport_sockets.tls"
              },
              "respectDnsTtl": true
            }
            """;

    //language=JSON
    private static final String endpointsJson =
            """
            {
              "clusterName": "my-cluster",
              "endpoints": [ {
                "locality": {
                  "zone": "zone1"
                },
                "lbEndpoints": [ {
                  "endpoint": {
                    "address": {
                      "socketAddress": {
                        "address": "127.0.0.1",
                        "portValue": %s
                      }
                    }
                  }
                } ]
              } ]
            }
            """;

    //language=YAML
    private static final String bootstrapYaml =
            """
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: bootstrap-cluster
                  lds_config:
                    ads: {}
                  cds_config:
                    ads: {}
                static_resources:
                  clusters:
                    - name: bootstrap-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: bootstrap-cluster
                        endpoints:
                        - lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: %s
                """;

    @Test
    void basicCase() throws Exception {
        final ClusterLoadAssignment loadAssignment =
                XdsResourceReader.fromJson(endpointsJson.formatted(server.httpPort()),
                                           ClusterLoadAssignment.class);
        final Cluster cluster =
                XdsResourceReader.fromJson(clusterJson.formatted(server.httpPort()), Cluster.class);
        final Listener listener = XdsResourceReader.fromJson(listenerJson, Listener.class);
        final RouteConfiguration route = XdsResourceReader.fromJson(routeJson, RouteConfiguration.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(loadAssignment),
                                                 ImmutableList.of(listener), ImmutableList.of(route),
                                                 ImmutableList.of(), version.toString()));

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml.formatted(server.httpPort()),
                                                               Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
        }
    }

    @Test
    void helloTest() throws Exception {
        final ClusterLoadAssignment loadAssignment =
                XdsResourceReader.fromJson(endpointsJson.formatted(server.httpPort()),
                                           ClusterLoadAssignment.class);
        final Cluster cluster =
                XdsResourceReader.fromJson(clusterJson.formatted(server.httpPort()), Cluster.class);
        final Listener listener = XdsResourceReader.fromJson(listenerJson, Listener.class);
        final RouteConfiguration route = XdsResourceReader.fromJson(routeJson, RouteConfiguration.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(loadAssignment),
                                                 ImmutableList.of(listener), ImmutableList.of(route),
                                                 ImmutableList.of(), version.toString()));

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml.formatted(server.httpPort()),
                                                               Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             EndpointGroup xdsEndpointGroup = XdsEndpointGroup.of("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res =
                    WebClient.of(SessionProtocol.HTTP, xdsEndpointGroup).blocking().get("/hello");
            assertThat(res.status().code()).isEqualTo(200);
        }
    }
}

/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.server.TransientServiceOption.WITH_SERVICE_LOGGING;
import static com.linecorp.armeria.xds.XdsTestResources.address;
import static com.linecorp.armeria.xds.XdsTestResources.createStaticCluster;
import static com.linecorp.armeria.xds.XdsTestResources.endpoint;
import static com.linecorp.armeria.xds.XdsTestResources.localityLbEndpoints;
import static com.linecorp.armeria.xds.XdsTestResources.staticResourceListener;
import static com.linecorp.armeria.xds.XdsTestUtil.pollLoadBalancer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.healthcheck.SettableHealthChecker;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsTestResources;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.core.v3.HealthCheck;
import io.envoyproxy.envoy.config.core.v3.HealthCheck.HttpHealthCheck;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint.HealthCheckConfig;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.type.v3.Percent;

class DynamicHealthCheckTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    private static final AtomicReference<ServiceRequestContext> server1Hc1CtxRef = new AtomicReference<>();
    private static final AtomicReference<ServiceRequestContext> server1Hc2CtxRef = new AtomicReference<>();

    private static final SettableHealthChecker server1Hc1 = new SettableHealthChecker();

    @RegisterExtension
    static ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.decorator(LoggingService.newDecorator());
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.route()
              .decorator((delegate, ctx, req) -> {
                  server1Hc1CtxRef.set(ctx);
                  return delegate.serve(ctx, req);
              })
              .path("/monitor/healthcheck")
              .build(HealthCheckService.builder().checkers(server1Hc1).build());
            sb.route()
              .decorator((delegate, ctx, req) -> {
                  server1Hc2CtxRef.set(ctx);
                  return delegate.serve(ctx, req);
              })
              .path("/monitor/healthcheck2")
              .build(HealthCheckService.builder()
                                       .checkers(server1Hc1)
                                       .transientServiceOptions(WITH_SERVICE_LOGGING)
                                       .build());

            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    static ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.decorator(LoggingService.newDecorator());
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.service("/monitor/healthcheck2",
                       HealthCheckService.builder()
                                         .transientServiceOptions(WITH_SERVICE_LOGGING)
                                         .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        server1Hc1.setHealthy(false);
        server1Hc1CtxRef.set(null);
        server1Hc2CtxRef.set(null);
    }

    @Test
    void pathUpdate() throws Exception {
        server1Hc1.setHealthy(true);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server1.httpUri());
        final Listener listener = staticResourceListener();
        final LbEndpoint endpoint1 = endpoint("127.0.0.1", server1.httpPort());
        final LbEndpoint endpoint2 = endpoint("127.0.0.1", server2.httpPort());
        final List<LbEndpoint> allEndpoints = ImmutableList.of(endpoint1, endpoint2);
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), allEndpoints))
                        .setPolicy(Policy.newBuilder().setWeightedPriorityHealth(true))
                        .build();
        final HealthCheck hc1 =
                HealthCheck.newBuilder()
                           .setHttpHealthCheck(HttpHealthCheck.newBuilder()
                                                              .setPath("/monitor/healthcheck"))
                           .build();
        Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .addHealthChecks(hc1)
                .setCommonLbConfig(CommonLbConfig.newBuilder()
                                                 .setHealthyPanicThreshold(Percent.newBuilder().setValue(0)))
                .build();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                                 ImmutableList.of(listener), ImmutableList.of(),
                                                 ImmutableList.of(), "v1"));
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {

            final XdsLoadBalancer loadBalancer1 = pollLoadBalancer(root, "cluster", cluster);
            assertThat(loadBalancer1).isNotNull();
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final Endpoint endpoint = loadBalancer1.select(ctx, ctx.eventLoop()).join();
            assertThat(endpoint.port()).isEqualTo(server1.httpPort());

            // now update the health check path
            server1Hc1.setHealthy(false);
            final HealthCheck hc2 =
                    HealthCheck.newBuilder()
                               .setHttpHealthCheck(HttpHealthCheck.newBuilder()
                                                                  .setPath("/monitor/healthcheck2"))
                               .build();
            cluster = createStaticCluster("cluster", loadAssignment)
                    .toBuilder().addHealthChecks(hc2).build();
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                                     ImmutableList.of(listener), ImmutableList.of(),
                                                     ImmutableList.of(), "v2"));

            final XdsLoadBalancer loadBalancer2 = pollLoadBalancer(root, "cluster", cluster);
            await().untilAsserted(() -> assertThat(loadBalancer2.select(ctx, ctx.eventLoop()).join().port())
                    .isEqualTo(server2.httpPort()));

            await().untilAsserted(() -> {
                // WeightRampingUpStrategy guarantees that all endpoints will be considered, so
                // trying 4 times should be more than enough
                for (int i = 0; i < 4; i++) {
                    // after the hc to the first server is updated, requests should only be routed to server2
                    assertThat(loadBalancer2.select(ctx, ctx.eventLoop()).join().port())
                            .isEqualTo(server2.httpPort());
                }
            });
        }
    }

    @Test
    void gracefulEndpointUpdate() throws Exception {
        server1Hc1.setHealthy(true);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server1.httpUri());
        final Listener listener = staticResourceListener();
        final LbEndpoint endpoint1 = endpoint("127.0.0.1", server1.httpPort());
        final LbEndpoint endpoint2 = endpoint("127.0.0.1", server2.httpPort());
        final List<LbEndpoint> allEndpoints = ImmutableList.of(endpoint1, endpoint2);
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), allEndpoints))
                        .setPolicy(Policy.newBuilder().setWeightedPriorityHealth(true))
                        .build();
        HealthCheck hc1 =
                HealthCheck.newBuilder()
                           .setHttpHealthCheck(HttpHealthCheck.newBuilder()
                                                              .setPath("/monitor/healthcheck"))
                           .build();
        Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .addHealthChecks(hc1)
                .setCommonLbConfig(CommonLbConfig.newBuilder()
                                                 .setHealthyPanicThreshold(Percent.newBuilder().setValue(0)))
                .build();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                                 ImmutableList.of(listener), ImmutableList.of(),
                                                 ImmutableList.of(), "v3"));
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            // WeightRampingUpStrategy guarantees that all endpoints will be considered, so
            // trying 4 times should be more than enough
            for (int i = 0; i < 4; i++) {
                final Endpoint endpoint = loadBalancer.select(ctx, CommonPools.workerGroup()).get();
                assertThat(endpoint.port()).isEqualTo(server1.httpPort());
            }
            assertThat(server1Hc2CtxRef).hasNullValue();

            // now update the cluster information
            hc1 = HealthCheck.newBuilder()
                             .setHttpHealthCheck(HttpHealthCheck.newBuilder()
                                                                .setPath("/monitor/healthcheck2"))
                             .build();
            cluster = createStaticCluster("cluster", loadAssignment)
                    .toBuilder()
                    .addHealthChecks(hc1)
                    .setCommonLbConfig(CommonLbConfig.newBuilder()
                                                     .setHealthyPanicThreshold(Percent.newBuilder()
                                                                                      .setValue(0)))
                    .build();
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                                     ImmutableList.of(listener), ImmutableList.of(),
                                                     ImmutableList.of(), "v4"));

            final XdsLoadBalancer loadBalancer2 = pollLoadBalancer(root, "cluster", cluster);
            // wait until server 2 is also selected
            await().untilAsserted(() -> {
                final Endpoint endpoint = loadBalancer2.select(ctx, CommonPools.workerGroup()).get();
                assertThat(endpoint.port()).isEqualTo(server2.httpPort());
            });

            // cancel the request for the first health check
            final RequestContext server1Hc1Ctx = server1Hc1CtxRef.get();
            assertThat(server1Hc1Ctx).isNotNull();
            server1Hc1Ctx.cancel();

            // check that server 1 connection hasn't been reset for the new health check path
            await().untilAsserted(() -> assertThat(server1Hc2CtxRef.get()).isNotNull());
            assertThat(server1Hc2CtxRef.get().remoteAddress()).isEqualTo(server1Hc1Ctx.remoteAddress());
        }
    }

    @Test
    void perEndpointHealthCheck() throws Exception {
        server1Hc1.setHealthy(false);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server1.httpUri());
        final Listener listener = staticResourceListener();
        final io.envoyproxy.envoy.config.endpoint.v3.Endpoint endpointWithServer2Hc =
                io.envoyproxy.envoy.config.endpoint.v3.Endpoint
                        .newBuilder()
                        .setAddress(address("127.0.0.1", server1.httpPort()))
                        .setHealthCheckConfig(HealthCheckConfig.newBuilder()
                                                               // portValue is used instead of the address port
                                                               .setAddress(address("127.0.0.1", 1234))
                                                               .setPortValue(server2.httpPort())).build();
        final LbEndpoint endpoint1 =
                LbEndpoint.newBuilder().setEndpoint(endpointWithServer2Hc).build();
        final LbEndpoint endpoint2 = endpoint("127.0.0.1", server2.httpPort());
        final List<LbEndpoint> allEndpoints = ImmutableList.of(endpoint1, endpoint2);
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), allEndpoints))
                        .setPolicy(Policy.newBuilder().setWeightedPriorityHealth(true))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .addHealthChecks(HealthCheck.newBuilder()
                                            .setHttpHealthCheck(
                                                    HttpHealthCheck.newBuilder()
                                                                   .setPath("/monitor/healthcheck2"))
                                            .build())
                .setCommonLbConfig(CommonLbConfig.newBuilder()
                                                 .setHealthyPanicThreshold(Percent.newBuilder().setValue(0)))
                .build();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                                 ImmutableList.of(listener), ImmutableList.of(),
                                                 ImmutableList.of(), "v3"));
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            // WeightRampingUpStrategy guarantees that all endpoints will be considered, so
            // trying 4 times should be more than enough
            final Set<Integer> healthyPorts = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                final Endpoint endpoint = loadBalancer.select(ctx, CommonPools.workerGroup()).get();
                healthyPorts.add(endpoint.port());
            }
            assertThat(healthyPorts).containsExactlyInAnyOrder(server1.httpPort(), server2.httpPort());
        }
    }
}

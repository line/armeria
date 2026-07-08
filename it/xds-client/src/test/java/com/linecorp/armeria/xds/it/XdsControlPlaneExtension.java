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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.extension.ExtensionContext;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.AbstractAllOrEachExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsBootstrapBuilder;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

/**
 * A JUnit 5 extension that manages an xDS control plane server, an internal
 * {@link XdsBootstrap}, and a mutable resource map. All standard discovery services
 * (ADS, LDS, RDS, CDS, EDS, SDS) are registered on a single ephemeral HTTP port.
 *
 * <p>Usage:
 * <pre>{@code
 * @RegisterExtension
 * @Order(0)
 * static final XdsControlPlaneExtension controlPlane = new XdsControlPlaneExtension();
 *
 * @RegisterExtension
 * @Order(1)
 * static final ServerExtension server = new ServerExtension() {
 *     protected void configure(ServerBuilder sb) {
 *         controlPlane.set(myListener);
 *         sb.plugin(XdsServerPlugin.of(controlPlane.bootstrap(), "my-listener"));
 *         sb.service("/hello", (ctx, req) -> HttpResponse.of("hello"));
 *     }
 * };
 *
 * @Test
 * void test() {
 *     String ver = controlPlane.set(updatedListener);
 *     controlPlane.awaitListener("my-listener", ver);
 *     // make assertions...
 * }
 * }</pre>
 */
public final class XdsControlPlaneExtension extends AbstractAllOrEachExtension {

    private static final String GROUP = "key";

    private final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private final AtomicLong version = new AtomicLong();
    private final Consumer<XdsBootstrapBuilder> bootstrapCustomizer;

    private List<Listener> listeners = ImmutableList.of();
    private List<Cluster> clusters = ImmutableList.of();
    private List<ClusterLoadAssignment> endpoints = ImmutableList.of();
    private List<RouteConfiguration> routes = ImmutableList.of();
    private List<Secret> secrets = ImmutableList.of();

    private Server server;
    private XdsBootstrap xdsBootstrap;

    public XdsControlPlaneExtension() {
        this(builder -> {});
    }

    public XdsControlPlaneExtension(Consumer<XdsBootstrapBuilder> bootstrapCustomizer) {
        this.bootstrapCustomizer = bootstrapCustomizer;
    }

    @Override
    protected void before(ExtensionContext context) throws Exception {
        final V3DiscoveryServer ds = new V3DiscoveryServer(cache);
        server = Server.builder()
                       .service(GrpcService.builder()
                                           .addService(ds.getAggregatedDiscoveryServiceImpl())
                                           .addService(ds.getListenerDiscoveryServiceImpl())
                                           .addService(ds.getRouteDiscoveryServiceImpl())
                                           .addService(ds.getClusterDiscoveryServiceImpl())
                                           .addService(ds.getEndpointDiscoveryServiceImpl())
                                           .addService(ds.getSecretDiscoveryServiceImpl())
                                           .build())
                       .http(0)
                       .build();
        server.start().join();

        final int port = server.activePort(SessionProtocol.HTTP).localAddress().getPort();
        //language=YAML
        final String yaml =
                """
                dynamic_resources:
                  ads_config:
                    api_type: AGGREGATED_GRPC
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: xds-cluster
                  lds_config:
                    ads: {}
                  cds_config:
                    ads: {}
                static_resources:
                  clusters:
                    - name: xds-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: xds-cluster
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: 127.0.0.1
                                      port_value: %d
                """.formatted(port);
        final XdsBootstrapBuilder builder =
                XdsBootstrap.builder(XdsResourceReader.fromYaml(yaml, Bootstrap.class));
        bootstrapCustomizer.accept(builder);
        xdsBootstrap = builder.build();
    }

    @Override
    protected void after(ExtensionContext context) throws Exception {
        if (xdsBootstrap != null) {
            xdsBootstrap.close();
        }
        if (server != null) {
            server.stop().join();
        }
    }

    public XdsBootstrap bootstrap() {
        return xdsBootstrap;
    }

    public SimpleCache<String> cache() {
        return cache;
    }

    public int httpPort() {
        return server.activePort(SessionProtocol.HTTP).localAddress().getPort();
    }

    public String set(Listener... listeners) {
        this.listeners = ImmutableList.copyOf(listeners);
        return pushSnapshot();
    }

    public String set(Cluster... clusters) {
        this.clusters = ImmutableList.copyOf(clusters);
        return pushSnapshot();
    }

    public String set(ClusterLoadAssignment... endpoints) {
        this.endpoints = ImmutableList.copyOf(endpoints);
        return pushSnapshot();
    }

    public String set(RouteConfiguration... routes) {
        this.routes = ImmutableList.copyOf(routes);
        return pushSnapshot();
    }

    public String set(Secret... secrets) {
        this.secrets = ImmutableList.copyOf(secrets);
        return pushSnapshot();
    }

    /**
     * Waits until the internal {@link XdsBootstrap} has received the specified version
     * for the given listener name.
     */
    public void awaitListener(String listenerName, String version) {
        try (ListenerRoot root = xdsBootstrap.listenerRoot(listenerName)) {
            final AtomicReference<ListenerSnapshot> ref = new AtomicReference<>();
            root.addSnapshotWatcher((snapshot, error) -> {
                if (snapshot != null) {
                    ref.set(snapshot);
                }
            });
            await().untilAsserted(() -> {
                final ListenerSnapshot snapshot = ref.get();
                assertThat(snapshot).isNotNull();
                assertThat(snapshot.xdsResource().version()).isEqualTo(version);
            });
        }
    }

    private String pushSnapshot() {
        final String ver = String.valueOf(version.incrementAndGet());
        cache.setSnapshot(GROUP, Snapshot.create(clusters, endpoints, listeners,
                                                 routes, secrets, ver));
        return ver;
    }
}

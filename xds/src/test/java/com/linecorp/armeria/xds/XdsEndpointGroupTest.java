/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import static com.linecorp.armeria.xds.XdsTestResources.BOOTSTRAP_CLUSTER_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.client.endpoint.XdsEndpointGroup;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

class XdsEndpointGroupTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final String clusterName = "cluster1";
    private static final String httpsClusterName = "https-cluster1";
    private static final String listenerName = "listener1";
    private static final String httpsListenerName = "https-listener1";
    private static final String routeName = "route1";
    private static final String httpsRouteName = "https-route1";
    private static final String httpsBootstrapClusterName = "https-bootstrap-cluster";

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
            sb.tlsSelfSigned();
            sb.http(0);
            sb.https(0);
        }
    };

    @RegisterExtension
    static final ServerExtension helloServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
            sb.tlsSelfSigned();
            sb.http(0);
            sb.https(0);
        }
    };

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @BeforeEach
    void beforeEach() {
        final Cluster httpCluster = XdsTestResources.createCluster(clusterName, 0);
        final Cluster httpsCluster = XdsTestResources.createCluster(httpsClusterName, 0);
        final ClusterLoadAssignment httpAssignment =
                XdsTestResources.loadAssignment(clusterName,
                                                helloServer.httpSocketAddress().getHostString(),
                                                helloServer.httpPort());
        final ClusterLoadAssignment httpsAssignment =
                XdsTestResources.loadAssignment(httpsClusterName,
                                                helloServer.httpSocketAddress().getHostString(),
                                                helloServer.httpsPort());
        final Listener httpListener =
                XdsTestResources.exampleListener(listenerName, routeName);
        final Listener httpsListener =
                XdsTestResources.exampleListener(httpsListenerName, httpsRouteName);
        final RouteConfiguration httpRoute = XdsTestResources.routeConfiguration(routeName, clusterName);
        final RouteConfiguration httpsRoute =
                XdsTestResources.routeConfiguration(httpsRouteName, httpsClusterName);
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(httpCluster, httpsCluster),
                        ImmutableList.of(httpAssignment, httpsAssignment),
                        ImmutableList.of(httpListener, httpsListener),
                        ImmutableList.of(httpRoute, httpsRoute),
                        ImmutableList.of(),
                        "1"));
    }

    @Test
    void testWithListener() {
        final ConfigSource configSource = XdsTestResources.basicConfigSource(BOOTSTRAP_CLUSTER_NAME);
        final URI uri = server.httpUri();
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(BOOTSTRAP_CLUSTER_NAME,
                                                uri.getHost(), uri.getPort());
        final Cluster bootstrapCluster =
                XdsTestResources.createStaticCluster(BOOTSTRAP_CLUSTER_NAME, loadAssignment);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final EndpointGroup xdsEndpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot(listenerName));
            final BlockingWebClient blockingClient = WebClient.of(SessionProtocol.HTTP, xdsEndpointGroup)
                                                              .blocking();
            assertThat(blockingClient.get("/hello").contentUtf8()).isEqualTo("world");
        }
    }

    @Test
    void testAllHttps() {
        final ConfigSource configSource = XdsTestResources.basicConfigSource(httpsBootstrapClusterName);
        final URI httpsUri = server.httpsUri();
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(httpsBootstrapClusterName,
                                                httpsUri.getHost(), httpsUri.getPort());
        final Cluster bootstrapCluster =
                XdsTestResources.createTlsStaticCluster(httpsBootstrapClusterName, loadAssignment);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, bootstrapCluster);
        final Consumer<GrpcClientBuilder> customizer = cb -> cb.factory(ClientFactory.insecure());
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap, eventLoop.get(), customizer)) {
            final EndpointGroup xdsEndpointGroup =
                    XdsEndpointGroup.of(xdsBootstrap.listenerRoot(httpsListenerName));
            final BlockingWebClient blockingClient = WebClient.builder(SessionProtocol.HTTPS, xdsEndpointGroup)
                                                              .factory(ClientFactory.insecure())
                                                              .build().blocking();
            assertThat(blockingClient.get("/hello").contentUtf8()).isEqualTo("world");
        }
    }

    @Test
    void testControlPlaneOnlyHttps() {
        final ConfigSource configSource = XdsTestResources.basicConfigSource(httpsBootstrapClusterName);
        final URI httpsUri = server.httpsUri();
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(httpsBootstrapClusterName,
                                                httpsUri.getHost(), httpsUri.getPort());
        final Cluster cluster = XdsTestResources.createTlsStaticCluster(httpsBootstrapClusterName,
                                                                        loadAssignment);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(
                XdsTestResources.bootstrap(configSource, cluster),
                eventLoop.get(), cb -> cb.factory(ClientFactory.insecure()))) {
            final EndpointGroup xdsEndpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot(listenerName));
            final BlockingWebClient blockingClient = WebClient.builder(SessionProtocol.HTTP, xdsEndpointGroup)
                                                              .factory(ClientFactory.insecure())
                                                              .build().blocking();
            assertThat(blockingClient.get("/hello").contentUtf8()).isEqualTo("world");
        }
    }
}

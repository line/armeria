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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.configsource.InterestedResources;
import com.linecorp.armeria.xds.configsource.SotwConfigSourceSubscriptionFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

class CustomConfigSourceTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    //language=JSON
    private static final String DISCOVERY_RESPONSE_JSON = """
            {
              "typeUrl": "type.googleapis.com/envoy.config.cluster.v3.Cluster",
              "resources": [
                {
                  "@type": "type.googleapis.com/envoy.config.cluster.v3.Cluster",
                  "name": "my-dynamic-cluster",
                  "type": "STATIC",
                  "loadAssignment": {
                    "clusterName": "my-dynamic-cluster",
                    "endpoints": [
                      {
                        "lbEndpoints": [
                          {
                            "endpoint": {
                              "address": {
                                "socketAddress": {
                                  "address": "127.0.0.1",
                                  "portValue": 9999
                                }
                              }
                            }
                          }
                        ]
                      }
                    ]
                  }
                }
              ],
              "versionInfo": "1"
            }
            """;

    @RegisterExtension
    static final ServerExtension configServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/xds/cluster/my-dynamic-cluster", (ctx, req) ->
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON, DISCOVERY_RESPONSE_JSON));
        }
    };

    @Test
    void customConfigSourceFetchesClusterViaHttp() {
        //language=YAML
        final String bootstrapYaml = """
                static_resources:
                  clusters:
                    - name: config-server
                      type: STATIC
                      load_assignment:
                        cluster_name: config-server
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: 127.0.0.1
                                      port_value: %d
                dynamic_resources:
                  cds_config:
                    custom_config_source:
                      "@type": "type.googleapis.com/google.protobuf.Empty"
                """.formatted(configServer.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml);
        final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final SnapshotWatcher<Object> watcher = (snapshot, t) -> {
            if (t != null) {
                errorRef.set(t);
                return;
            }
            if (snapshot instanceof ClusterSnapshot) {
                snapshotRef.set((ClusterSnapshot) snapshot);
            }
        };

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .eventExecutor(eventLoop.get())
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            xdsBootstrap.clusterRoot("my-dynamic-cluster");
            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                assertThat(snapshotRef.get()).isNotNull();
                assertThat(snapshotRef.get().xdsResource().resource().getName())
                        .isEqualTo("my-dynamic-cluster");
            });
        }
    }

    public static final class HttpConfigSourceFactory implements SotwConfigSourceSubscriptionFactory {

        @Override
        public String name() {
            return "http-config-source";
        }

        @Override
        public List<String> typeUrls() {
            return ImmutableList.of("type.googleapis.com/google.protobuf.Empty");
        }

        @Override
        public SnapshotStream<DiscoveryResponse> create(
                ConfigSource configSource,
                FactoryContext factoryContext,
                SnapshotStream<InterestedResources> interestedResources) {
            final SnapshotStream<ClusterSnapshot> clusterStream =
                    factoryContext.clusterStream("config-server");
            return SnapshotStream.combineLatest(interestedResources, clusterStream, Map::entry)
                                 .switchMapEager(entry -> fetchViaHttp(entry.getKey(),
                                                                       entry.getValue()));
        }

        private SnapshotStream<DiscoveryResponse> fetchViaHttp(InterestedResources interest,
                                                                ClusterSnapshot clusterSnapshot) {
            return watcher -> {
                final WebClient client = WebClient.of(clusterSnapshot.preprocessor());
                final String type = interest.type().name().toLowerCase(Locale.ENGLISH);
                for (String name : interest.resourceNames()) {
                    client.get("/xds/%s/%s".formatted(type, name)).aggregate().handle((response, cause) -> {
                        if (cause != null) {
                            watcher.onUpdate(null, cause);
                            return null;
                        }
                        try {
                            final DiscoveryResponse discoveryResponse =
                                    XdsResourceReader.fromJson(
                                            response.contentUtf8(), DiscoveryResponse.class);
                            watcher.onUpdate(discoveryResponse, null);
                        } catch (Exception e) {
                            watcher.onUpdate(null, e);
                        }
                        return null;
                    });
                }
                return Subscription.noop();
            };
        }
    }
}

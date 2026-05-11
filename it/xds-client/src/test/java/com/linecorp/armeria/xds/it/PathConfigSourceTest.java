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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class PathConfigSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void clusterFromJsonPathConfigSource() throws Exception {
        //language=JSON
        final String json = """
                {
                  "typeUrl": "type.googleapis.com/envoy.config.cluster.v3.Cluster",
                  "resources": [
                    {
                      "@type": "type.googleapis.com/envoy.config.cluster.v3.Cluster",
                      "name": "path-cluster",
                      "type": "STATIC",
                      "loadAssignment": {
                        "clusterName": "path-cluster",
                        "endpoints": [
                          {
                            "lbEndpoints": [
                              {
                                "endpoint": {
                                  "address": {
                                    "socketAddress": {
                                      "address": "127.0.0.1",
                                      "portValue": 8080
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

        final Path targetFile = tempDir.resolve("clusters.json");
        Files.writeString(targetFile, json);

        //language=YAML
        final String bootstrapYaml =
                """
                dynamic_resources:
                  cds_config:
                    path_config_source:
                      path: %s
                """.formatted(targetFile);

        verifyClusterFromPathConfigSource(bootstrapYaml);
    }

    @Test
    void clusterFromYamlPathConfigSource() throws Exception {
        //language=YAML
        final String yaml = """
                typeUrl: "type.googleapis.com/envoy.config.cluster.v3.Cluster"
                resources:
                  - "@type": "type.googleapis.com/envoy.config.cluster.v3.Cluster"
                    name: path-cluster
                    type: STATIC
                    loadAssignment:
                      clusterName: path-cluster
                      endpoints:
                        - lbEndpoints:
                            - endpoint:
                                address:
                                  socketAddress:
                                    address: 127.0.0.1
                                    portValue: 8080
                versionInfo: "1"
                """;

        final Path targetFile = tempDir.resolve("clusters.yaml");
        Files.writeString(targetFile, yaml);

        //language=YAML
        final String bootstrapYaml =
                """
                dynamic_resources:
                  cds_config:
                    path_config_source:
                      path: %s
                """.formatted(targetFile);

        verifyClusterFromPathConfigSource(bootstrapYaml);
    }

    private static void verifyClusterFromPathConfigSource(String bootstrapYaml) {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
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
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            xdsBootstrap.clusterRoot("path-cluster");
            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                assertThat(snapshotRef.get()).isNotNull();
                assertThat(snapshotRef.get().xdsResource().resource().getName())
                        .isEqualTo("path-cluster");
            });
        }
    }
}

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
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.xds.ClusterRoot;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.DataSourcePolicy;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.TlsCertificateSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class DataSourcePolicyTest {

    @RegisterExtension
    static final XdsCertificateExtension certificate =
            new XdsCertificateExtension(new SelfSignedCertificateExtension());

    @Test
    void fileDataSourceAllowedUnderRootDir(@TempDir Path tempDir) throws Exception {
        final Path certsDir = copyCerts(tempDir.resolve("certs"));

        final Bootstrap bootstrap = bootstrapYaml(certsDir);
        final DataSourcePolicy policy = DataSourcePolicy.builder()
                                                        .allowedRootDirs(certsDir)
                                                        .build();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .dataSourcePolicy(policy)
                                                     .build()) {
            final TlsCertificateSnapshot certSnapshot = awaitCertSnapshot(xdsBootstrap);
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate.tlsKeyPair());
        }
    }

    @Test
    void fileDataSourceBlockedOutsideRootDir(@TempDir Path tempDir,
                                             @TempDir Path allowedDir) throws Exception {
        final Path certsDir = copyCerts(tempDir.resolve("certs"));

        final Bootstrap bootstrap = bootstrapYaml(certsDir);
        final DataSourcePolicy policy = DataSourcePolicy.builder()
                                                        .allowedRootDirs(allowedDir)
                                                        .build();
        final Throwable error = awaitError(bootstrap, policy);
        assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not under any allowed root directory");
    }

    @Test
    void pathTraversalIsNormalizedAndBlocked(@TempDir Path tempDir) throws Exception {
        final Path certsDir = copyCerts(tempDir.resolve("certs"));
        final Path subDir = certsDir.resolve("sub");
        Files.createDirectories(subDir);

        // sub/../private_key.pem resolves to certsDir/private_key.pem after normalization,
        // which is outside the allowed root (sub/)
        final Bootstrap bootstrap = bootstrapYaml(subDir.resolve(".."));
        final DataSourcePolicy policy = DataSourcePolicy.builder()
                                                        .allowedRootDirs(subDir)
                                                        .build();
        final Throwable error = awaitError(bootstrap, policy);
        assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not under any allowed root directory");
    }

    @Test
    void allowAllPolicyPermitsEverything(@TempDir Path tempDir) throws Exception {
        final Path certsDir = copyCerts(tempDir.resolve("certs"));

        final Bootstrap bootstrap = bootstrapYaml(certsDir);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .dataSourcePolicy(DataSourcePolicy.allowAll())
                                                     .build()) {
            final TlsCertificateSnapshot certSnapshot = awaitCertSnapshot(xdsBootstrap);
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate.tlsKeyPair());
        }
    }

    private static TlsCertificateSnapshot awaitCertSnapshot(XdsBootstrap xdsBootstrap) {
        final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot("my-cluster");
        final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
        clusterRoot.addSnapshotWatcher((snapshot, t) -> {
            if (snapshot != null) {
                snapshotRef.set(snapshot);
            }
        });
        await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
        return snapshotRef.get().transportSocket().tlsCertificate();
    }

    /**
     * Uses {@code defaultSnapshotWatcher} to capture errors that fire synchronously
     * during subscription (before {@code addSnapshotWatcher} can be called).
     */
    private static Throwable awaitError(Bootstrap bootstrap, DataSourcePolicy policy) {
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final var watcher = new SnapshotWatcher<>() {
            @Override
            public void onUpdate(@Nullable Object snapshot, @Nullable Throwable t) {
                if (t != null) {
                    errorRef.set(t);
                }
            }
        };
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .dataSourcePolicy(policy)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            xdsBootstrap.clusterRoot("my-cluster");
            await().untilAsserted(() -> assertThat(errorRef.get()).isNotNull());
        }
        return errorRef.get();
    }

    private Path copyCerts(Path certsDir) throws Exception {
        Files.createDirectories(certsDir);
        Files.copy(certificate.privateKeyFile().toPath(), certsDir.resolve("private_key.pem"),
                   StandardCopyOption.REPLACE_EXISTING);
        Files.copy(certificate.certificateFile().toPath(), certsDir.resolve("certificate.pem"),
                   StandardCopyOption.REPLACE_EXISTING);
        return certsDir;
    }

    private static Bootstrap bootstrapYaml(Path certsDir) {
        //language=YAML
        final String bootstrapStr =
                """
                static_resources:
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
                                  port_value: 8080
                      transport_socket:
                        name: envoy.transport_sockets.tls
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                          common_tls_context:
                            tls_certificates:
                              - private_key:
                                  filename: '%s'
                                certificate_chain:
                                  filename: '%s'
                """.formatted(certsDir.resolve("private_key.pem"),
                              certsDir.resolve("certificate.pem"));
        return XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
    }
}

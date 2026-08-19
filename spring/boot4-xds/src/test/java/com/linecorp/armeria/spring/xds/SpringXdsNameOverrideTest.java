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

package com.linecorp.armeria.spring.xds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

/**
 * Tests that the property key name overrides the resource's own {@code name}
 * (or {@code cluster_name} for endpoints) so that subscribers always receive
 * resources under the correct key.
 */
@SpringBootTest(classes = SpringXdsNameOverrideTest.TestApp.class)
@ActiveProfiles("xds-name-override-test")
class SpringXdsNameOverrideTest {

    @SpringBootApplication
    static class TestApp {
    }

    @Autowired
    XdsBootstrap xdsBootstrap;

    @Test
    void clusterNameOverriddenByPropertyKey() {
        final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
        xdsBootstrap.clusterRoot("test-cluster")
                    .addSnapshotWatcher((snapshot, error) -> {
                        if (snapshot != null) {
                            snapshotRef.set(snapshot);
                        }
                    });

        await().untilAsserted(() -> {
            assertThat(snapshotRef.get()).isNotNull();
            // The YAML has name: wrong-cluster-name, but the property key is test-cluster.
            assertThat(snapshotRef.get().xdsResource().resource().getName())
                    .isEqualTo("test-cluster");
        });
    }

    @Test
    void endpointClusterNameOverriddenByPropertyKey() {
        final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
        xdsBootstrap.clusterRoot("test-eds-cluster")
                    .addSnapshotWatcher((snapshot, error) -> {
                        if (snapshot != null) {
                            snapshotRef.set(snapshot);
                        }
                    });

        await().untilAsserted(() -> {
            assertThat(snapshotRef.get()).isNotNull();
            // The cluster YAML has name: wrong-eds-cluster-name, but the property key is test-eds-cluster.
            assertThat(snapshotRef.get().xdsResource().resource().getName())
                    .isEqualTo("test-eds-cluster");
            // The endpoint YAML has cluster_name: wrong-endpoint-name, but the property key is test-endpoint.
            assertThat(snapshotRef.get().endpointSnapshot().xdsResource().resource().getClusterName())
                    .isEqualTo("test-endpoint");
        });
    }
}

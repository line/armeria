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

@SpringBootTest(classes = SpringXdsCustomBootstrapTest.TestApp.class)
@ActiveProfiles("xds-custom-bootstrap-test")
class SpringXdsCustomBootstrapTest {

    @SpringBootApplication
    static class TestApp {
    }

    @Autowired
    XdsBootstrap xdsBootstrap;

    @Test
    void loadClusterWithCustomPrefix() {
        final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
        xdsBootstrap.clusterRoot("custom-cluster")
                    .addSnapshotWatcher((snapshot, error) -> {
                        if (snapshot != null) {
                            snapshotRef.set(snapshot);
                        }
                    });

        await().untilAsserted(() -> {
            assertThat(snapshotRef.get()).isNotNull();
            assertThat(snapshotRef.get().xdsResource().resource().getName())
                    .isEqualTo("custom-cluster");
            assertThat(snapshotRef.get().xdsResource().resource()
                                  .getLoadAssignment()
                                  .getEndpoints(0)
                                  .getLbEndpoints(0)
                                  .getEndpoint()
                                  .getAddress()
                                  .getSocketAddress()
                                  .getPortValue())
                    .isEqualTo(9090);
        });
    }
}

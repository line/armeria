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

package com.linecorp.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.URI;

import org.junit.jupiter.api.Test;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class RootWatcherTest {

    @Test
    void testClosedRoot() {
        final String resourceName = "cluster1";
        final Bootstrap bootstrap = XdsTestResources.bootstrap(URI.create("http://a.b:80"));
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final TestResourceWatcher watcher = new TestResourceWatcher();
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(resourceName);
            clusterRoot.close();
            await().untilAsserted(() -> assertThat(clusterRoot.closed()).isTrue());
            assertThatThrownBy(() -> clusterRoot.addSnapshotWatcher(watcher))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("can't be registered since ClusterRoot is already closed.");
        }
    }

    @Test
    void testNullWatcher() {
        final String resourceName = "cluster1";
        final Bootstrap bootstrap = XdsTestResources.bootstrap(URI.create("http://a.b:80"));
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(resourceName);
            clusterRoot.close();
            assertThatThrownBy(() -> clusterRoot.addSnapshotWatcher(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

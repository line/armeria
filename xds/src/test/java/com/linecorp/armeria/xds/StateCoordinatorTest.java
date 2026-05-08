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

package com.linecorp.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

class StateCoordinatorTest {

    private static final String CLUSTER_NAME = "cluster1";
    private static final String ROUTE_NAME = "route1";

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void lateSubscriberReceivesCachedResource() {
        final XdsExtensionRegistry extensionRegistry = XdsExtensionRegistry.of(new XdsResourceValidator());
        final StateCoordinator coordinator = new StateCoordinator(eventLoop.get(), 15_000, false,
                                                                  extensionRegistry);
        final ClusterXdsResource resource =
                new ClusterXdsResource(createCluster(CLUSTER_NAME), "1").withRevision(1);
        coordinator.onResourceUpdated(XdsType.CLUSTER, CLUSTER_NAME, resource);

        final CapturingWatcher watcher = new CapturingWatcher();
        coordinator.register(XdsType.CLUSTER, CLUSTER_NAME, watcher);

        assertThat(watcher.changed).isSameAs(resource);
        assertThat(watcher.missingType).isNull();
    }

    @Test
    void missingResourceNotCachedAfterRemoval() {
        final XdsExtensionRegistry extensionRegistry = XdsExtensionRegistry.of(new XdsResourceValidator());
        final StateCoordinator coordinator = new StateCoordinator(eventLoop.get(), 15_000, false,
                                                                  extensionRegistry);
        final CapturingWatcher watcher1 = new CapturingWatcher();
        coordinator.register(XdsType.CLUSTER, CLUSTER_NAME, watcher1);

        coordinator.onResourceMissing(XdsType.CLUSTER, CLUSTER_NAME);
        coordinator.unregister(XdsType.CLUSTER, CLUSTER_NAME, watcher1);

        // After missing + unregister, the state is removed from stateStore.
        // A new watcher should not get a replay — it waits for the server.
        final CapturingWatcher watcher2 = new CapturingWatcher();
        coordinator.register(XdsType.CLUSTER, CLUSTER_NAME, watcher2);

        assertThat(watcher2.changed).isNull();
        assertThat(watcher2.missingType).isNull();
        assertThat(watcher2.missingName).isNull();
    }

    @Test
    void stateRetainedAfterUnsubscribe() {
        final XdsExtensionRegistry extensionRegistry = XdsExtensionRegistry.of(new XdsResourceValidator());
        final StateCoordinator coordinator = new StateCoordinator(eventLoop.get(), 15_000, false,
                                                                  extensionRegistry);
        final RouteXdsResource resource =
                new RouteXdsResource(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build(), "1")
                        .withRevision(1);
        coordinator.onResourceUpdated(XdsType.ROUTE, ROUTE_NAME, resource);

        final CapturingWatcher watcher1 = new CapturingWatcher();
        coordinator.register(XdsType.ROUTE, ROUTE_NAME, watcher1);
        assertThat(watcher1.changed).isSameAs(resource);

        // Unregister does not touch stateStore, so the cached resource remains.
        coordinator.unregister(XdsType.ROUTE, ROUTE_NAME, watcher1);

        final CapturingWatcher watcher2 = new CapturingWatcher();
        coordinator.register(XdsType.ROUTE, ROUTE_NAME, watcher2);

        assertThat(watcher2.changed).isSameAs(resource);
        assertThat(watcher2.missingType).isNull();
    }

    private static Cluster createCluster(String name) {
        return Cluster.newBuilder().setName(name).build();
    }

    private static final class CapturingWatcher implements ResourceWatcher<XdsResource> {
        @Nullable
        private XdsResource changed;
        @Nullable
        private XdsType missingType;
        @Nullable
        private String missingName;

        @Override
        public void onChanged(XdsResource update) {
            changed = update;
        }

        @Override
        public void onResourceDoesNotExist(XdsType type, String resourceName) {
            missingType = type;
            missingName = resourceName;
        }
    }
}

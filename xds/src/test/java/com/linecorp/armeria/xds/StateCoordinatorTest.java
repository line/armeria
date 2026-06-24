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

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.file.DirectoryWatchService;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class StateCoordinatorTest {

    private static final String CLUSTER_NAME = "cluster1";
    private static final String ROUTE_NAME = "route1";

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    private static final DirectoryWatchService watchService = new DirectoryWatchService();

    @AfterAll
    static void tearDown() {
        watchService.close();
    }

    @Test
    void dataDeliveredToSubscribedWatcher() {
        final XdsExtensionRegistry extensionRegistry = extensionRegistry();
        final StateCoordinator coordinator = new StateCoordinator(
                eventLoop.get(), ConfigSource.getDefaultInstance(), false, extensionRegistry);
        final ClusterXdsResource resource =
                new ClusterXdsResource(createCluster(CLUSTER_NAME), "1").withRevision(1);

        final AtomicReference<XdsResource> changed = new AtomicReference<>();
        final SnapshotWatcher<XdsResource> watcher = (value, error) -> {
            if (value != null) {
                changed.set(value);
            }
        };
        coordinator.register(XdsType.CLUSTER, CLUSTER_NAME, watcher);

        // onResourceUpdated stores and notifies the subscribed watcher
        coordinator.onResourceUpdated(XdsType.CLUSTER, CLUSTER_NAME, resource);

        assertThat(changed.get()).isNotNull();
        assertThat(changed.get().resource()).isEqualTo(resource.resource());
    }

    @Test
    void registerDeliversCachedResource() {
        final XdsExtensionRegistry extensionRegistry = extensionRegistry();
        final StateCoordinator coordinator = new StateCoordinator(
                eventLoop.get(), ConfigSource.getDefaultInstance(), false, extensionRegistry);
        final ClusterXdsResource resource =
                new ClusterXdsResource(createCluster(CLUSTER_NAME), "1").withRevision(1);

        // First register + store a resource
        final SnapshotWatcher<XdsResource> noopWatcher = (value, error) -> {};
        coordinator.register(XdsType.CLUSTER, CLUSTER_NAME, noopWatcher);
        coordinator.onResourceUpdated(XdsType.CLUSTER, CLUSTER_NAME, resource);

        // A late watcher registered to the same resource gets the cached value
        final AtomicReference<XdsResource> replayed = new AtomicReference<>();
        final SnapshotWatcher<XdsResource> lateWatcher = (value, error) -> {
            if (value != null) {
                replayed.set(value);
            }
        };
        coordinator.register(XdsType.CLUSTER, CLUSTER_NAME, lateWatcher);
        assertThat(replayed.get()).isNotNull();
        assertThat(replayed.get().resource()).isEqualTo(resource.resource());
    }

    @Test
    void missingResourceNotCachedAfterRemoval() {
        final XdsExtensionRegistry extensionRegistry = extensionRegistry();
        final StateCoordinator coordinator = new StateCoordinator(
                eventLoop.get(), ConfigSource.getDefaultInstance(), false, extensionRegistry);
        final SnapshotWatcher<XdsResource> noopWatcher = (value, error) -> {};
        coordinator.register(XdsType.CLUSTER, CLUSTER_NAME, noopWatcher);

        coordinator.onResourceMissing(XdsType.CLUSTER, CLUSTER_NAME);
        coordinator.unregister(XdsType.CLUSTER, CLUSTER_NAME, noopWatcher);

        // After missing + unregister, a new register should not deliver anything
        final AtomicReference<XdsResource> changed = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final SnapshotWatcher<XdsResource> newWatcher = (value, err) -> {
            if (value != null) {
                changed.set(value);
            }
            if (err != null) {
                error.set(err);
            }
        };
        coordinator.register(XdsType.CLUSTER, CLUSTER_NAME, newWatcher);

        assertThat(changed.get()).isNull();
        assertThat(error.get()).isNull();
    }

    @Test
    void stateRetainedAfterUnsubscribe() {
        final XdsExtensionRegistry extensionRegistry = extensionRegistry();
        final StateCoordinator coordinator = new StateCoordinator(
                eventLoop.get(), ConfigSource.getDefaultInstance(), false, extensionRegistry);
        final RouteXdsResource resource =
                new RouteXdsResource(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build(), "1")
                        .withRevision(1);

        final AtomicReference<XdsResource> changed1 = new AtomicReference<>();
        final SnapshotWatcher<XdsResource> watcher1 = (value, err) -> {
            if (value != null) {
                changed1.set(value);
            }
        };
        coordinator.register(XdsType.ROUTE, ROUTE_NAME, watcher1);

        coordinator.onResourceUpdated(XdsType.ROUTE, ROUTE_NAME, resource);
        assertThat(changed1.get()).isNotNull();

        // Unregister removes the watcher and the subscriber slot.
        assertThat(coordinator.unregister(XdsType.ROUTE, ROUTE_NAME, watcher1)).isTrue();

        // stateStore retains the resource even after subscriber is removed.
        // Re-register with a new watcher delivers the cached value.
        final AtomicReference<XdsResource> changed2 = new AtomicReference<>();
        final SnapshotWatcher<XdsResource> watcher2 = (value, err) -> {
            if (value != null) {
                changed2.set(value);
            }
        };
        coordinator.register(XdsType.ROUTE, ROUTE_NAME, watcher2);
        assertThat(changed2.get()).isNotNull();
        assertThat(changed2.get().resource()).isEqualTo(resource.resource());
    }

    private static XdsExtensionRegistry extensionRegistry() {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        return XdsExtensionRegistry.of(new XdsResourceValidator(),
                                       watchService,
                                       meterRegistry,
                                       new MeterIdPrefix("test"),
                                       ImmutableList.of());
    }

    private static Cluster createCluster(String name) {
        return Cluster.newBuilder().setName(name).build();
    }
}

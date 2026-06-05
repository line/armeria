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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

class SubscriberStorageTest {

    private static final String CLUSTER_NAME = "cluster1";
    private static final String ROUTE_NAME = "route1";

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void registerAndUnregister() {
        final SubscriberStorage storage = new SubscriberStorage(eventLoop.get(), 15_000, false);
        final SnapshotWatcher<XdsResource> watcher = (value, error) -> {};
        storage.register(XdsType.CLUSTER, CLUSTER_NAME, watcher);
        assertThat(storage.resources(XdsType.CLUSTER)).hasSize(1);
        storage.unregister(XdsType.CLUSTER, CLUSTER_NAME, watcher);
        assertThat(storage.resources(XdsType.CLUSTER)).isEmpty();
        assertThat(storage.hasNoSubscribers()).isTrue();
    }

    @Test
    void duplicateRegisterReturnsFalse() {
        final SubscriberStorage storage = new SubscriberStorage(eventLoop.get(), 15_000, false);
        final SnapshotWatcher<XdsResource> watcher1 = (value, error) -> {};
        final SnapshotWatcher<XdsResource> watcher2 = (value, error) -> {};
        assertThat(storage.register(XdsType.CLUSTER, CLUSTER_NAME, watcher1)).isTrue();
        assertThat(storage.resources(XdsType.CLUSTER)).hasSize(1);
        assertThat(storage.register(XdsType.CLUSTER, CLUSTER_NAME, watcher2)).isFalse();
        assertThat(storage.resources(XdsType.CLUSTER)).hasSize(1);

        storage.unregister(XdsType.CLUSTER, CLUSTER_NAME, watcher1);
        // watcher2 still present, so slot not removed yet
        assertThat(storage.hasNoSubscribers()).isFalse();
        storage.unregister(XdsType.CLUSTER, CLUSTER_NAME, watcher2);
        assertThat(storage.resources(XdsType.CLUSTER)).isEmpty();
        assertThat(storage.hasNoSubscribers()).isTrue();
    }

    @Test
    void nonClusterListenerTimeout() {
        final SubscriberStorage storage = new SubscriberStorage(eventLoop.get(), 50, false);

        final AtomicReference<XdsType> missingType = new AtomicReference<>();
        final AtomicReference<String> missingName = new AtomicReference<>();
        final SnapshotWatcher<XdsResource> watcher = (value, error) -> {
            if (error instanceof MissingXdsResourceException) {
                missingType.set(((MissingXdsResourceException) error).type());
                missingName.set(((MissingXdsResourceException) error).name());
            }
        };
        storage.register(XdsType.ROUTE, ROUTE_NAME, watcher);

        await().atMost(1, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(missingType.get()).isEqualTo(XdsType.ROUTE);
                   assertThat(missingName.get()).isEqualTo(ROUTE_NAME);
               });
    }
}

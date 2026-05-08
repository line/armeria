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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

class SubscriberStorageTest {

    private static final String CLUSTER_NAME = "cluster1";
    private static final String ROUTE_NAME = "route1";

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void registerAndUnregister() throws Exception {
        final DummyResourceWatcher watcher = new DummyResourceWatcher();
        final SubscriberStorage storage = new SubscriberStorage(eventLoop.get(), 15_000, false);
        storage.register(XdsType.CLUSTER, CLUSTER_NAME, watcher);
        assertThat(storage.resources(XdsType.CLUSTER)).hasSize(1);
        storage.unregister(XdsType.CLUSTER, CLUSTER_NAME, watcher);
        assertThat(storage.resources(XdsType.CLUSTER)).isEmpty();
        assertThat(storage.hasNoSubscribers()).isTrue();
    }

    @Test
    void identityBasedUnregister() {
        final DummyResourceWatcher watcher1 = new DummyResourceWatcher();
        final SubscriberStorage storage = new SubscriberStorage(eventLoop.get(), 15_000, false);
        storage.register(XdsType.CLUSTER, CLUSTER_NAME, watcher1);
        assertThat(storage.resources(XdsType.CLUSTER)).hasSize(1);
        storage.register(XdsType.CLUSTER, CLUSTER_NAME, watcher1);
        assertThat(storage.resources(XdsType.CLUSTER)).hasSize(1);

        storage.unregister(XdsType.CLUSTER, CLUSTER_NAME, watcher1);
        assertThat(storage.resources(XdsType.CLUSTER)).isEmpty();
        assertThat(storage.hasNoSubscribers()).isTrue();
    }

    @Test
    void nonClusterListenerTimeout() {
        final CapturingWatcher watcher = new CapturingWatcher();
        final SubscriberStorage storage = new SubscriberStorage(eventLoop.get(), 50, false);
        storage.register(XdsType.ROUTE, ROUTE_NAME, watcher);

        await().atMost(1, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(watcher.missingType).isEqualTo(XdsType.ROUTE);
                   assertThat(watcher.missingName).isEqualTo(ROUTE_NAME);
               });
    }

    private static final class CapturingWatcher implements ResourceWatcher<XdsResource> {
        private volatile XdsType missingType;
        private volatile String missingName;

        @Override
        public void onChanged(XdsResource update) {}

        @Override
        public void onResourceDoesNotExist(XdsType type, String resourceName) {
            missingType = type;
            missingName = resourceName;
        }
    }
}

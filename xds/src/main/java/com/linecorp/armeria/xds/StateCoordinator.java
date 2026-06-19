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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.netty.util.concurrent.EventExecutor;

final class StateCoordinator implements SafeCloseable {

    private final SubscriberStorage subscriberStorage;
    private final ResourceStateStore stateStore;
    private final XdsExtensionRegistry extensionRegistry;

    StateCoordinator(EventExecutor eventLoop, ConfigSource configSource,
                     boolean delta, XdsExtensionRegistry extensionRegistry) {
        final long timeoutMillis = initialFetchTimeoutMillis(configSource);
        subscriberStorage = new SubscriberStorage(eventLoop, timeoutMillis, delta);
        stateStore = new ResourceStateStore();
        this.extensionRegistry = extensionRegistry;
    }

    XdsExtensionRegistry extensionRegistry() {
        return extensionRegistry;
    }

    private static long initialFetchTimeoutMillis(ConfigSource configSource) {
        if (!configSource.hasInitialFetchTimeout()) {
            return 15_000;
        }
        final Duration timeoutDuration = configSource.getInitialFetchTimeout();
        final long epochMilli = Durations.toMillis(timeoutDuration);
        checkArgument(epochMilli >= 0, "Invalid initialFetchTimeout received: %s (expected >= 0)",
                      timeoutDuration);
        return epochMilli;
    }

    <T extends XdsResource> boolean register(XdsType type, String resourceName,
                                             SnapshotWatcher<T> watcher) {
        final boolean updated = subscriberStorage.register(type, resourceName, watcher);
        replayToWatcher(type, resourceName, watcher);
        return updated;
    }

    <T extends XdsResource> boolean unregister(XdsType type, String resourceName,
                                               SnapshotWatcher<T> watcher) {
        return subscriberStorage.unregister(type, resourceName, watcher);
    }

    private <T extends XdsResource> void replayToWatcher(XdsType type, String resourceName,
                                                         SnapshotWatcher<T> watcher) {
        @SuppressWarnings("unchecked")
        final T cached = (T) stateStore.resource(type, resourceName);
        if (cached != null) {
            watcher.onUpdate(cached, null);
        }
    }

    ImmutableSet<String> interestedResources(XdsType type) {
        return subscriberStorage.resources(type);
    }

    boolean hasNoSubscribers() {
        return subscriberStorage.hasNoSubscribers();
    }

    ImmutableSet<String> activeResources(XdsType type) {
        return stateStore.activeResources(type);
    }

    ImmutableMap<String, String> resourceVersions(XdsType type) {
        return stateStore.resourceVersions(type);
    }

    void onResourceUpdated(XdsType type, String resourceName, XdsResource resource) {
        final XdsResource revised = stateStore.put(type, resourceName, resource);
        if (revised == null) {
            return;
        }
        final CompositeSnapshotWatcher<XdsResource> subscriber =
                subscriberStorage.subscriber(type, resourceName);
        if (subscriber != null) {
            subscriber.onUpdate(revised, null);
        }
    }

    void onResourceMissing(XdsType type, String resourceName) {
        if (!stateStore.remove(type, resourceName)) {
            return;
        }
        final CompositeSnapshotWatcher<?> subscriber = subscriberStorage.subscriber(type, resourceName);
        if (subscriber != null) {
            subscriber.onUpdate(null, new MissingXdsResourceException(type, resourceName));
        }
    }

    void onResourceError(XdsType type, String resourceName, Throwable cause) {
        final CompositeSnapshotWatcher<?> subscriber = subscriberStorage.subscriber(type, resourceName);
        if (subscriber != null) {
            subscriber.onUpdate(null, XdsResourceException.maybeWrap(type, resourceName, cause));
        }
    }

    @Override
    public void close() {
        subscriberStorage.close();
    }
}

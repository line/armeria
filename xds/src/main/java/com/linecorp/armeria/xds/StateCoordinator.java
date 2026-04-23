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

import java.time.Instant;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Duration;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.netty.util.concurrent.EventExecutor;

final class StateCoordinator implements SafeCloseable {

    private final SubscriberStorage subscriberStorage;
    private final ResourceStateStore stateStore;

    StateCoordinator(EventExecutor eventLoop, ConfigSource configSource, boolean delta) {
        this(eventLoop, initialFetchTimeoutMillis(configSource), delta);
    }

    StateCoordinator(EventExecutor eventLoop, long timeoutMillis, boolean delta) {
        subscriberStorage = new SubscriberStorage(eventLoop, timeoutMillis, delta);
        stateStore = new ResourceStateStore();
    }

    private static long initialFetchTimeoutMillis(ConfigSource configSource) {
        if (!configSource.hasInitialFetchTimeout()) {
            return 15_000;
        }
        final Duration timeoutDuration = configSource.getInitialFetchTimeout();
        final Instant instant = Instant.ofEpochSecond(timeoutDuration.getSeconds(),
                                                      timeoutDuration.getNanos());
        final long epochMilli = instant.toEpochMilli();
        checkArgument(epochMilli >= 0, "Invalid initialFetchTimeout received: %s (expected >= 0)",
                      timeoutDuration);
        return epochMilli;
    }

    <T extends XdsResource> boolean register(XdsType type, String resourceName, ResourceWatcher<T> watcher) {
        final boolean updated = subscriberStorage.register(type, resourceName, watcher);
        replayToWatcher(type, resourceName, watcher);
        return updated;
    }

    <T extends XdsResource> boolean unregister(XdsType type, String resourceName, ResourceWatcher<T> watcher) {
        return subscriberStorage.unregister(type, resourceName, watcher);
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
        final XdsStreamSubscriber<XdsResource> subscriber = subscriber(type, resourceName);
        if (subscriber != null) {
            subscriber.onData(revised);
        }
    }

    void onResourceMissing(XdsType type, String resourceName) {
        if (!stateStore.remove(type, resourceName)) {
            return;
        }
        final XdsStreamSubscriber<?> subscriber = subscriber(type, resourceName);
        if (subscriber != null) {
            subscriber.onAbsent();
        }
    }

    void onResourceError(XdsType type, String resourceName, Throwable cause) {
        final XdsStreamSubscriber<?> subscriber = subscriber(type, resourceName);
        if (subscriber != null) {
            subscriber.onError(resourceName, cause);
        }
    }

    @Nullable
    private <T extends XdsResource> XdsStreamSubscriber<T> subscriber(XdsType type, String resourceName) {
        return subscriberStorage.subscriber(type, resourceName);
    }

    private <T extends XdsResource> void replayToWatcher(XdsType type, String resourceName,
                                                         ResourceWatcher<T> watcher) {
        final XdsResource resource = stateStore.resource(type, resourceName);
        if (resource != null) {
            //noinspection unchecked
            watcher.onChanged((T) resource);
        }
    }

    @Override
    public void close() {
        subscriberStorage.close();
    }
}

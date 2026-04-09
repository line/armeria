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

import java.util.EnumSet;
import java.util.Set;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.concurrent.EventExecutor;

final class StateCoordinator implements SafeCloseable {

    private static final Set<XdsType> FULL_SOTW_TYPES = EnumSet.of(XdsType.LISTENER, XdsType.CLUSTER);

    private final SubscriberStorage subscriberStorage;
    private final ResourceStateStore stateStore;
    private final boolean delta;

    StateCoordinator(EventExecutor eventLoop, long timeoutMillis, boolean delta) {
        this.delta = delta;
        subscriberStorage = new SubscriberStorage(eventLoop, timeoutMillis, delta);
        stateStore = new ResourceStateStore();
    }

    <T extends XdsResource> boolean register(XdsType type, String resourceName, ResourceWatcher<T> watcher) {
        final boolean updated = subscriberStorage.register(type, resourceName, watcher);
        replayToWatcher(type, resourceName, watcher);
        return updated;
    }

    <T extends XdsResource> boolean unregister(XdsType type, String resourceName, ResourceWatcher<T> watcher) {
        final boolean removed = subscriberStorage.unregister(type, resourceName, watcher);
        if (removed) {
            if (!delta && !FULL_SOTW_TYPES.contains(type)) {
                // For sotw, we don't receive missing signals for non-sotw types,
                // so removal is done when no subscribers are left.
                stateStore.remove(type, resourceName);
            } else {
                stateStore.removeIfWaiting(type, resourceName);
            }
        }
        return removed;
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
        final XdsResource revised = stateStore.putVersioned(type, resourceName, resource);
        if (revised == null) {
            return;
        }
        final XdsStreamSubscriber<XdsResource> subscriber = subscriber(type, resourceName);
        if (subscriber != null) {
            subscriber.onData(revised);
        }
    }

    void onResourceMissing(XdsType type, String resourceName) {
        if (!stateStore.putAbsent(type, resourceName)) {
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
        final ResourceStateStore.ResourceState state = stateStore.state(type, resourceName);
        if (state == null) {
            stateStore.putWaiting(type, resourceName);
            return;
        }
        switch (state.status()) {
            case VERSIONED:
                assert state.resource() != null;
                //noinspection unchecked
                watcher.onChanged((T) state.resource());
                break;
            case ABSENT:
                watcher.onResourceDoesNotExist(type, resourceName);
                break;
            case WAITING_FOR_SERVER:
                break;
        }
    }

    @Override
    public void close() {
        subscriberStorage.close();
    }
}

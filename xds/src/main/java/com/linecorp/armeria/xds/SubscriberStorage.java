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

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.concurrent.EventExecutor;

final class SubscriberStorage implements SafeCloseable {

    private final EventExecutor eventLoop;
    private final long timeoutMillis;

    private final Map<XdsType, Map<String, XdsStreamSubscriber<?>>> subscriberMap =
            new EnumMap<>(XdsType.class);

    SubscriberStorage(EventExecutor eventLoop, long timeoutMillis) {
        this.eventLoop = eventLoop;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Returns {@code true} if a new subscriber is added.
     */
    <T extends XdsResource> boolean register(XdsType type, String resourceName, ResourceWatcher<T> watcher) {
        //noinspection unchecked
        XdsStreamSubscriber<T> subscriber = (XdsStreamSubscriber<T>) subscriberMap.computeIfAbsent(
                type, key -> new HashMap<>()).get(resourceName);
        boolean updated = false;
        if (subscriber == null) {
            subscriber = new XdsStreamSubscriber<>(type, resourceName, eventLoop, timeoutMillis);
            subscriberMap.get(type).put(resourceName, subscriber);
            updated = true;
        }
        subscriber.registerWatcher(watcher);
        return updated;
    }

    /**
     * Returns {@code true} if a subscriber is removed.
     */
    <T extends XdsResource> boolean unregister(XdsType type, String resourceName, ResourceWatcher<T> watcher) {
        if (!subscriberMap.containsKey(type)) {
            return false;
        }
        final Map<String, XdsStreamSubscriber<?>> resourceToSubscriber = subscriberMap.get(type);
        if (!resourceToSubscriber.containsKey(resourceName)) {
            return false;
        }
        //noinspection unchecked
        final XdsStreamSubscriber<T> subscriber =
                (XdsStreamSubscriber<T>) resourceToSubscriber.get(resourceName);
        subscriber.unregisterWatcher(watcher);
        if (subscriber.isEmpty()) {
            resourceToSubscriber.remove(resourceName);
            subscriber.close();
            if (resourceToSubscriber.isEmpty()) {
                subscriberMap.remove(type);
            }
            return true;
        }
        return false;
    }

    <T extends XdsResource> Map<String, XdsStreamSubscriber<T>> subscribers(XdsType type) {
        return unsafeCast(subscriberMap.getOrDefault(type, Collections.emptyMap()));
    }

    static <T> T unsafeCast(Object obj) {
        //noinspection unchecked
        return (T) obj;
    }

    Set<String> resources(XdsType type) {
        return subscriberMap.getOrDefault(type, Collections.emptyMap()).keySet();
    }

    Map<XdsType, Map<String, XdsStreamSubscriber<?>>> allSubscribers() {
        return subscriberMap;
    }

    @Override
    public void close() {
        subscriberMap.values().forEach(subscribers -> {
            subscribers.values().forEach(XdsStreamSubscriber::close);
        });
        subscriberMap.clear();
    }
}

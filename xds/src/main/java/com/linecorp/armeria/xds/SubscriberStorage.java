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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

final class SubscriberStorage implements SafeCloseable {

    // Accessed only from a single eventLoop
    private final Map<XdsType, Map<String, XdsStreamSubscriber>> subscriberMap =
            new HashMap<>();

    @Nullable
    boolean register(XdsType type, String resourceName) {
        if (!subscriberMap.containsKey(type)) {
            subscriberMap.put(type, new HashMap<>());
        }
        XdsStreamSubscriber subscriber =
                subscriberMap.get(type).get(resourceName);
        if (subscriber != null) {
            subscriber.incRef();
            return false;
        }
        subscriber = new XdsStreamSubscriber();
        subscriberMap.get(type).put(resourceName, subscriber);
        return true;
    }

    @Nullable
    boolean unregister(XdsType type, String resourceName) {
        if (!subscriberMap.containsKey(type)) {
            return false;
        }
        final Map<String, XdsStreamSubscriber> resourceToSubscriber = subscriberMap.get(type);
        if (!resourceToSubscriber.containsKey(resourceName)) {
            return false;
        }
        final XdsStreamSubscriber subscriber = resourceToSubscriber.get(resourceName);
        subscriber.decRef();
        if (subscriber.reference() == 0) {
            resourceToSubscriber.remove(resourceName);
            subscriber.close();
            if (resourceToSubscriber.isEmpty()) {
                subscriberMap.remove(type);
            }
            return true;
        }
        return false;
    }

    Map<String, XdsStreamSubscriber> subscribers(XdsType type) {
        return subscriberMap.getOrDefault(type, Collections.emptyMap());
    }

    Set<String> resources(XdsType type) {
        return subscriberMap.getOrDefault(type, Collections.emptyMap()).keySet();
    }

    Map<XdsType, Map<String, XdsStreamSubscriber>> allSubscribers() {
        return subscriberMap;
    }

    @Override
    public void close() {
        subscriberMap.clear();
    }
}

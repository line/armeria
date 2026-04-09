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

final class ConfigSourceHandler implements SubscriptionHandler {

    private final StateCoordinator stateCoordinator;
    private final ConfigSourceSubscription stream;

    ConfigSourceHandler(StateCoordinator stateCoordinator, ConfigSourceSubscription stream) {
        this.stateCoordinator = stateCoordinator;
        this.stream = stream;
    }

    @Override
    public void addSubscriber(XdsType type, String resourceName, ResourceWatcher<?> watcher) {
        if (stateCoordinator.register(type, resourceName, watcher)) {
            stream.updateInterests(type, stateCoordinator.interestedResources(type));
        }
    }

    @Override
    public boolean removeSubscriber(XdsType type, String resourceName, ResourceWatcher<?> watcher) {
        if (stateCoordinator.unregister(type, resourceName, watcher)) {
            stream.updateInterests(type, stateCoordinator.interestedResources(type));
        }
        return stateCoordinator.hasNoSubscribers();
    }

    @Override
    public void close() {
        stream.close();
        stateCoordinator.close();
    }
}

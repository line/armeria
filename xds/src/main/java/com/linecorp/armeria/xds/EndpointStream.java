/*
 * Copyright 2025 LY Corporation
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

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;

final class EndpointStream extends RefCountedStream<EndpointSnapshot> {

    @Nullable
    private final EndpointXdsResource endpointXdsResource;
    private final SubscriptionContext context;
    @Nullable
    private final ConfigSource configSource;
    private final String resourceName;

    EndpointStream(ConfigSource configSource,
                   String resourceName, SubscriptionContext context) {
        this.configSource = configSource;
        this.resourceName = resourceName;
        this.context = context;
        endpointXdsResource = null;
    }

    EndpointStream(EndpointXdsResource endpointXdsResource, SubscriptionContext context) {
        this.endpointXdsResource = endpointXdsResource;
        this.context = context;
        configSource = null;
        resourceName = endpointXdsResource.name();
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<EndpointSnapshot> watcher) {
        if (endpointXdsResource != null) {
            watcher.onUpdate(new EndpointSnapshot(endpointXdsResource), null);
            return Subscription.noop();
        }
        assert configSource != null;
        return new ResourceNodeAdapter<EndpointXdsResource>(configSource, context,
                                                            resourceName, XdsType.ENDPOINT)
                .map(EndpointSnapshot::new)
                .subscribe(watcher);
    }
}

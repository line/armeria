/*
 * Copyright 2025 LINE Corporation
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

import com.linecorp.armeria.xds.client.endpoint.XdsClusterManager;

import io.netty.util.concurrent.EventExecutor;

final class DefaultSubscriptionContext implements SubscriptionContext {

    private final EventExecutor eventLoop;
    private final XdsClusterManager clusterManager;
    private final BootstrapClusters bootstrapClusters;
    private final ConfigSourceMapper configSourceMapper;
    private final ConfigSourceManager configSourceManager;

    DefaultSubscriptionContext(EventExecutor eventLoop, XdsClusterManager clusterManager,
                               BootstrapClusters bootstrapClusters, ConfigSourceMapper configSourceMapper,
                               ConfigSourceManager configSourceManager) {
        this.eventLoop = eventLoop;
        this.clusterManager = clusterManager;
        this.bootstrapClusters = bootstrapClusters;
        this.configSourceMapper = configSourceMapper;
        this.configSourceManager = configSourceManager;
    }

    @Override
    public EventExecutor eventLoop() {
        return eventLoop;
    }

    @Override
    public void subscribe(ResourceNode<?> node) {
        configSourceManager.subscribe(node);
    }

    @Override
    public void unsubscribe(ResourceNode<?> node) {
        configSourceManager.unsubscribe(node);
    }

    @Override
    public ConfigSourceMapper configSourceMapper() {
        return configSourceMapper;
    }

    @Override
    public BootstrapClusters bootstrapClusters() {
        return bootstrapClusters;
    }

    @Override
    public XdsClusterManager clusterManager() {
        return clusterManager;
    }
}

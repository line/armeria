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

final class StaticSubscriptionContext implements SubscriptionContext {

    private final EventExecutor eventLoop;
    private final XdsClusterManager clusterManager;

    StaticSubscriptionContext(EventExecutor eventLoop, XdsClusterManager clusterManager) {
        this.eventLoop = eventLoop;
        this.clusterManager = clusterManager;
    }

    @Override
    public XdsClusterManager clusterManager() {
        return clusterManager;
    }

    @Override
    public EventExecutor eventLoop() {
        return eventLoop;
    }

    @Override
    public void subscribe(ResourceNode<?> node) {
        final String message = String.format("'%s.subscribe()' method is not supported for '%s' of type '%s'",
                                             getClass().getSimpleName(), node.name(), node.type());
        throw new UnsupportedOperationException(message);
    }

    @Override
    public void unsubscribe(ResourceNode<?> node) {
        final String message =
                String.format("'%s.unsubscribe()' method is not supported for '%s' of type '%s'",
                              getClass().getSimpleName(), node.name(), node.type());
        throw new UnsupportedOperationException(message);
    }

    @Override
    public BootstrapClusters bootstrapClusters() {
        final String message =
                String.format("'%s.bootstrapClusters()' method is not supported", getClass().getSimpleName());
        throw new UnsupportedOperationException(message);
    }

    @Override
    public ConfigSourceMapper configSourceMapper() {
        final String message =
                String.format("'%s.configSourceMapper()' method is not supported", getClass().getSimpleName());
        throw new UnsupportedOperationException(message);
    }
}

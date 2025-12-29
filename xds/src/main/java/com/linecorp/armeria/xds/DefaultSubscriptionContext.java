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

import com.linecorp.armeria.common.file.DirectoryWatchService;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.concurrent.EventExecutor;

final class DefaultSubscriptionContext implements SubscriptionContext {

    private final EventExecutor eventLoop;
    private final XdsClusterManager clusterManager;
    private final ConfigSourceMapper configSourceMapper;
    private final ControlPlaneClientManager controlPlaneClientManager;
    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix meterIdPrefix;
    private final DirectoryWatchService watchService;
    private final BootstrapSecrets bootstrapSecrets;

    DefaultSubscriptionContext(EventExecutor eventLoop, XdsClusterManager clusterManager,
                               ConfigSourceMapper configSourceMapper,
                               ControlPlaneClientManager controlPlaneClientManager,
                               MeterRegistry meterRegistry, MeterIdPrefix meterIdPrefix,
                               DirectoryWatchService watchService, BootstrapSecrets bootstrapSecrets) {
        this.eventLoop = eventLoop;
        this.clusterManager = clusterManager;
        this.configSourceMapper = configSourceMapper;
        this.controlPlaneClientManager = controlPlaneClientManager;
        this.meterRegistry = meterRegistry;
        this.meterIdPrefix = meterIdPrefix;
        this.watchService = watchService;
        this.bootstrapSecrets = bootstrapSecrets;
    }

    @Override
    public EventExecutor eventLoop() {
        return eventLoop;
    }

    @Override
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    @Override
    public MeterIdPrefix meterIdPrefix() {
        return meterIdPrefix;
    }

    @Override
    public void subscribe(ResourceNode<?> node) {
        controlPlaneClientManager.subscribe(node);
    }

    @Override
    public void unsubscribe(ResourceNode<?> node) {
        controlPlaneClientManager.unsubscribe(node);
    }

    @Override
    public ConfigSourceMapper configSourceMapper() {
        return configSourceMapper;
    }

    @Override
    public XdsClusterManager clusterManager() {
        return clusterManager;
    }

    @Override
    public DirectoryWatchService watchService() {
        return watchService;
    }

    @Override
    public BootstrapSecrets bootstrapSecrets() {
        return bootstrapSecrets;
    }
}

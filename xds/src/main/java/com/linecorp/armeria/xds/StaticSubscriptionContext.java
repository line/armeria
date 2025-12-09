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

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.concurrent.EventExecutor;

final class StaticSubscriptionContext implements SubscriptionContext {

    private final EventExecutor eventLoop;
    private final MeterIdPrefix meterIdPrefix;
    private final MeterRegistry meterRegistry;

    StaticSubscriptionContext(EventExecutor eventLoop, MeterIdPrefix meterIdPrefix,
                              MeterRegistry meterRegistry) {
        this.eventLoop = eventLoop;
        this.meterIdPrefix = meterIdPrefix;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public XdsClusterManager clusterManager() {
        final String message = String.format("'%s.clusterManager()' method is not supported",
                                             getClass().getSimpleName());
        throw new UnsupportedOperationException(message);
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
    public ConfigSourceMapper configSourceMapper() {
        final String message =
                String.format("'%s.configSourceMapper()' method is not supported", getClass().getSimpleName());
        throw new UnsupportedOperationException(message);
    }
}

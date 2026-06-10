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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.RefCountedStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;

final class ResourceNodeAdapter<T extends XdsResource> extends RefCountedStream<T> implements ResourceNode<T> {

    private final ConfigSource configSource;
    private final SubscriptionContext context;
    private final String name;
    private final XdsType type;
    private final ResourceNodeMeterBinderFactory.ResourceNodeMeterBinder resourceNodeMeterBinder;

    ResourceNodeAdapter(ConfigSource configSource,
                        SubscriptionContext context,
                        String name, XdsType type) {
        this.configSource = configSource;
        this.context = context;
        this.name = name;
        this.type = type;
        resourceNodeMeterBinder = context.meterBinderFactory().acquire(type, name);
    }

    @Override
    public ConfigSource configSource() {
        return configSource;
    }

    @Override
    public XdsType type() {
        return type;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public FactoryContext factoryContext() {
        return context;
    }

    @Override
    public void onUpdate(@Nullable T value, @Nullable Throwable error) {
        if (value != null) {
            resourceNodeMeterBinder.onChanged(value);
        } else if (error instanceof MissingXdsResourceException) {
            resourceNodeMeterBinder.onResourceDoesNotExist();
        } else if (error != null) {
            resourceNodeMeterBinder.onError();
        }
        emit(value, error);
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<T> watcher) {
        try {
            context.subscribe(this);
        } catch (Throwable t) {
            if (t instanceof XdsResourceException) {
                throw t;
            } else {
                throw new XdsResourceException(type, name, t, "Failed to subscribe to '" + configSource + '\'');
            }
        }
        return () -> {
            resourceNodeMeterBinder.close();
            context.unsubscribe(this);
        };
    }
}

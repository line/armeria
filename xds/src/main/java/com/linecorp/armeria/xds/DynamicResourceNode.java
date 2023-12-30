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

import java.util.ArrayDeque;
import java.util.Deque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.grpc.Status;

abstract class DynamicResourceNode<U extends ResourceHolder<?>> implements ResourceNode<U> {

    private static final Logger logger = LoggerFactory.getLogger(DynamicResourceNode.class);

    private final Deque<ResourceNode<?>> children = new ArrayDeque<>();

    static ResourceNode<?> from(@Nullable ConfigSource configSource, XdsType type,
                                String resourceName, WatchersStorage watchersStorage) {
        if (type == XdsType.LISTENER) {
            return new ListenerResourceNode(configSource, resourceName, watchersStorage);
        } else if (type == XdsType.ROUTE) {
            return new RouteResourceNode(configSource, resourceName, watchersStorage);
        } else if (type == XdsType.CLUSTER) {
            return new ClusterResourceNode(configSource, resourceName, watchersStorage);
        } else if (type == XdsType.ENDPOINT) {
            return new EndpointResourceNode(configSource, resourceName, watchersStorage);
        } else {
            throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }

    private final WatchersStorage watchersStorage;
    @Nullable
    private final ConfigSource configSource;
    private final XdsType type;
    private final String resourceName;
    @Nullable
    private U current;
    boolean initialized;

    DynamicResourceNode(WatchersStorage watchersStorage, @Nullable ConfigSource configSource,
                        XdsType type, String resourceName) {
        this.watchersStorage = watchersStorage;
        this.configSource = configSource;
        this.type = type;
        this.resourceName = resourceName;
    }

    public WatchersStorage watchersStorage() {
        return watchersStorage;
    }

    void setCurrent(@Nullable U current) {
        this.current = current;
    }

    @Override
    public U current() {
        return current;
    }

    @Override
    public void onError(XdsType type, Status error) {
        logger.warn("Unexpected error while watching {}: {}.", type, error);
    }

    @Override
    public void onResourceDoesNotExist(XdsType type, String resourceName) {
        initialized = true;
        setCurrent(null);

        for (ResourceNode<?> child: children) {
            child.close();
        }
        children.clear();
        watchersStorage.notifyListeners(type, resourceName);
    }

    @Override
    public final void onChanged(U update) {
        initialized = true;
        setCurrent(update);

        final Deque<ResourceNode<?>> prevChildren = new ArrayDeque<>(children);
        children.clear();

        process(update);

        for (ResourceNode<?> child: prevChildren) {
            child.close();
        }
        watchersStorage.notifyListeners(update.type(), update.name());
    }

    abstract void process(U update);

    @Override
    public boolean initialized() {
        return initialized;
    }

    @Override
    public void close() {
        for (ResourceNode<?> child: children) {
            child.close();
        }
        children.clear();
        watchersStorage.unsubscribe(configSource, type, resourceName, this);
    }

    public Deque<ResourceNode<?>> children() {
        return children;
    }
}

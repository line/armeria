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

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.grpc.Status;

abstract class AbstractResourceNode<T extends XdsResource> implements ResourceNode<T> {

    private final Deque<ResourceNode<?>> children = new ArrayDeque<>();

    private final XdsBootstrapImpl xdsBootstrap;
    @Nullable
    private final ConfigSource configSource;
    private final XdsType type;
    private final String resourceName;
    private final SnapshotWatcher<? extends Snapshot<T>> parentWatcher;
    private final ResourceNodeType resourceNodeType;
    @Nullable
    private T current;

    AbstractResourceNode(XdsBootstrapImpl xdsBootstrap, @Nullable ConfigSource configSource,
                         XdsType type, String resourceName,
                         SnapshotWatcher<? extends Snapshot<T>> parentWatcher,
                         ResourceNodeType resourceNodeType) {
        this.xdsBootstrap = xdsBootstrap;
        this.configSource = configSource;
        this.type = type;
        this.resourceName = resourceName;
        this.parentWatcher = parentWatcher;
        this.resourceNodeType = resourceNodeType;
    }

    XdsBootstrapImpl xdsBootstrap() {
        return xdsBootstrap;
    }

    @Override
    public ConfigSource configSource() {
        return configSource;
    }

    private void setCurrent(@Nullable T current) {
        this.current = current;
    }

    @Override
    public T currentResource() {
        return current;
    }

    @Override
    public void onError(XdsType type, Status error) {
        parentWatcher.onError(type, error);
    }

    @Override
    public void onResourceDoesNotExist(XdsType type, String resourceName) {
        setCurrent(null);

        for (ResourceNode<?> child: children) {
            child.close();
        }
        children.clear();
        parentWatcher.onMissing(type, resourceName);
    }

    @Override
    public void onChanged(T update) {
        assert update.type() == type();
        setCurrent(update);

        final Deque<ResourceNode<?>> prevChildren = new ArrayDeque<>(children);
        children.clear();

        doOnChanged(update);

        for (ResourceNode<?> child: prevChildren) {
            child.close();
        }
    }

    abstract void doOnChanged(T update);

    @Override
    public void close() {
        for (ResourceNode<?> child: children) {
            child.close();
        }
        children.clear();
        if (resourceNodeType == ResourceNodeType.DYNAMIC) {
            xdsBootstrap.unsubscribe(this);
        }
    }

    Deque<ResourceNode<?>> children() {
        return children;
    }

    @Override
    public XdsType type() {
        return type;
    }

    @Override
    public String name() {
        return resourceName;
    }
}

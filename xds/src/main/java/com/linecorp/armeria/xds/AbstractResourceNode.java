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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.HashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.grpc.Status;

abstract class AbstractResourceNode<T extends XdsResource, S extends Snapshot<T>> implements ResourceNode<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractResourceNode.class);

    private final SubscriptionContext context;
    @Nullable
    private final ConfigSource configSource;
    private final XdsType type;
    private final String resourceName;
    private final Set<SnapshotWatcher<S>> watchers = new HashSet<>();
    private final ResourceNodeType resourceNodeType;
    @Nullable
    private S snapshot;

    AbstractResourceNode(SubscriptionContext context, @Nullable ConfigSource configSource,
                         XdsType type, String resourceName, SnapshotWatcher<S> parentWatcher,
                         ResourceNodeType resourceNodeType) {
        if (resourceNodeType == ResourceNodeType.DYNAMIC) {
            checkArgument(configSource != null, "Dynamic node <%s.%s> received a null config source",
                          type, resourceName);
        } else if (resourceNodeType == ResourceNodeType.STATIC) {
            checkArgument(configSource == null, "Static node <%s.%s> received a config source <%s>",
                          type, resourceName, configSource);
        }
        this.context = context;
        this.configSource = configSource;
        this.type = type;
        this.resourceName = resourceName;
        this.resourceNodeType = resourceNodeType;
        watchers.add(parentWatcher);
    }

    AbstractResourceNode(SubscriptionContext context, @Nullable ConfigSource configSource,
                         XdsType type, String resourceName, ResourceNodeType resourceNodeType) {
        this.context = context;
        this.configSource = configSource;
        this.type = type;
        this.resourceName = resourceName;
        this.resourceNodeType = resourceNodeType;
    }

    final SubscriptionContext context() {
        return context;
    }

    @Nullable
    @Override
    public final ConfigSource configSource() {
        return configSource;
    }

    final void addWatcher(SnapshotWatcher<S> watcher) {
        watchers.add(watcher);
        if (snapshot != null) {
            watcher.snapshotUpdated(snapshot);
        }
    }

    final void removeWatcher(SnapshotWatcher<S> watcher) {
        watchers.remove(watcher);
    }

    final boolean hasWatchers() {
        return !watchers.isEmpty();
    }

    @Override
    public final void onError(XdsType type, Status error) {
        notifyOnError(type, error);
    }

    final void notifyOnError(XdsType type, Status error) {
        for (SnapshotWatcher<S> watcher : watchers) {
            try {
                watcher.onError(type, error);
            } catch (Exception e) {
                logger.warn("Unexpected exception notifying <{}> for 'onError' <{},{}> for error <{}> e: ",
                            watcher, resourceName, type, error, e);
            }
        }
    }

    @Override
    public final void onResourceDoesNotExist(XdsType type, String resourceName) {
        notifyOnMissing(type, resourceName);
    }

    final void notifyOnMissing(XdsType type, String resourceName) {
        for (SnapshotWatcher<S> watcher : watchers) {
            try {
                watcher.onMissing(type, resourceName);
            } catch (Exception e) {
                logger.warn("Unexpected exception notifying <{}> for 'onMissing' <{},{}> e: ",
                            watcher, resourceName, type, e);
            }
        }
    }

    @Override
    public final void onChanged(T update) {
        assert update.type() == type();
        doOnChanged(update);
    }

    abstract void doOnChanged(T update);

    final void notifyOnChanged(S snapshot) {
        this.snapshot = snapshot;
        for (SnapshotWatcher<S> watcher : watchers) {
            try {
                watcher.snapshotUpdated(snapshot);
            } catch (Exception e) {
                logger.warn("Unexpected exception notifying <{}> for 'snapshotUpdated' <{}> e: ",
                            watcher, snapshot, e);
            }
        }
    }

    @Override
    public void close() {
        if (resourceNodeType == ResourceNodeType.DYNAMIC) {
            context.unsubscribe(this);
        }
    }

    @Override
    public final XdsType type() {
        return type;
    }

    @Override
    public final String name() {
        return resourceName;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("context", context)
                          .add("configSource", configSource)
                          .add("type", type)
                          .add("resourceName", resourceName)
                          .add("watchers", watchers)
                          .add("resourceNodeType", resourceNodeType)
                          .add("snapshot", snapshot)
                          .toString();
    }
}

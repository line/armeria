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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;

abstract class AbstractResourceNode<T extends XdsResource, S extends Snapshot<T>> implements ResourceNode<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractResourceNode.class);

    private final SubscriptionContext context;
    @Nullable
    private final ConfigSource configSource;
    private final XdsType type;
    private final String resourceName;
    private final Set<SnapshotWatcher<? super S>> watchers = new HashSet<>();
    private final ResourceNodeType resourceNodeType;
    @Nullable
    private S snapshot;
    private final ResourceNodeMeterBinder meterBinder;

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
        meterBinder = new ResourceNodeMeterBinder(context.meterRegistry(), context.meterIdPrefix(),
                                                  type, resourceName);
        watchers.add(parentWatcher);
    }

    AbstractResourceNode(SubscriptionContext context, @Nullable ConfigSource configSource,
                         XdsType type, String resourceName, ResourceNodeType resourceNodeType) {
        this.context = context;
        this.configSource = configSource;
        this.type = type;
        this.resourceName = resourceName;
        this.resourceNodeType = resourceNodeType;
        meterBinder = new ResourceNodeMeterBinder(context.meterRegistry(),
                                                  context.meterIdPrefix(), type, resourceName);
    }

    final SubscriptionContext context() {
        return context;
    }

    @Nullable
    @Override
    public final ConfigSource configSource() {
        return configSource;
    }

    final void addWatcher(SnapshotWatcher<? super S> watcher) {
        watchers.add(watcher);
        if (snapshot != null) {
            watcher.onUpdate(snapshot, null);
        }
    }

    final void removeWatcher(SnapshotWatcher<S> watcher) {
        watchers.remove(watcher);
    }

    final boolean hasWatchers() {
        return !watchers.isEmpty();
    }

    @Override
    public final void onError(XdsType type, String resourceName, Throwable error) {
        notifyOnError(error);
        meterBinder.onError(type, resourceName, error);
    }

    final void notifyOnError(Throwable error) {
        final XdsResourceException exception = XdsResourceException.maybeWrap(type(), name(), error);
        for (SnapshotWatcher<? super S> watcher : watchers) {
            try {
                watcher.onUpdate(null, exception);
            } catch (Exception e) {
                logger.warn("Unexpected exception notifying <{}> for 'onError' <{},{}> for error <{}> e: ",
                            watcher, resourceName, type, error, e);
            }
        }
    }

    @Override
    public final void onResourceDoesNotExist(XdsType type, String resourceName) {
        notifyOnMissing(type, resourceName);
        meterBinder.onResourceDoesNotExist(type, resourceName);
    }

    final void notifyOnMissing(XdsType type, String resourceName) {
        final MissingXdsResourceException exception = new MissingXdsResourceException(type, resourceName);
        for (SnapshotWatcher<? super S> watcher : watchers) {
            try {
                watcher.onUpdate(null, exception);
            } catch (Exception e) {
                logger.warn("Unexpected exception notifying <{}> for 'onMissing' <{},{}> e: ",
                            watcher, resourceName, type, e);
            }
        }
    }

    @Override
    public final void onChanged(T update) {
        assert update.type() == type();
        try {
            doOnChanged(update);
            meterBinder.onChanged(update);
        } catch (Throwable t) {
            notifyOnError(t);
            meterBinder.onError(type, resourceName, t);
        }
    }

    abstract void doOnChanged(T update);

    final void notifyOnChanged(S snapshot) {
        this.snapshot = snapshot;
        for (SnapshotWatcher<? super S> watcher : watchers) {
            try {
                watcher.onUpdate(snapshot, null);
            } catch (Exception e) {
                logger.warn("Unexpected exception notifying <{}> for 'snapshotUpdated' <{}> e: ",
                            watcher, snapshot, e);
            }
        }
    }

    void preClose() {
        meterBinder.close();
    }

    @Override
    public void close() {
        meterBinder.close();
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

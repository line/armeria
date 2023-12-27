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

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

final class WatchersStorage {

    private static final Logger logger = LoggerFactory.getLogger(WatchersStorage.class);

    private static final Object NOOP = new Object();

    private final Map<XdsType, Map<String, LinkedHashSet<ResourceNode<?>>>> storageMap =
            new HashMap<>();

    private final Map<XdsType, Map<String, CompositeWatcher>> watchers = new HashMap<>();

    @Nullable
    Object current(XdsType type, String resource) {
        if (!storageMap.containsKey(type)) {
            return null;
        }
        final Map<String, LinkedHashSet<ResourceNode<?>>> nodesMap = this.storageMap.get(type);
        if (!nodesMap.containsKey(resource)) {
            return null;
        }
        Object ret = NOOP;
        final LinkedHashSet<ResourceNode<?>> nodes = nodesMap.get(resource);
        for (ResourceNode<?> node: nodes) {
            final Object candidate = node.current();
            if (candidate != null) {
                ret = candidate;
                break;
            }
            if (node.initialized()) {
                ret = null;
            }
        }
        return ret;
    }

    void notifyListeners(XdsType type, String resource) {
        final Map<String, CompositeWatcher> resourceToWatchers =
                watchers.computeIfAbsent(type, ignored -> new HashMap<>());
        final CompositeWatcher compositeWatcher =
                resourceToWatchers.computeIfAbsent(
                        resource, ignored -> new CompositeWatcher(type, resource));
        compositeWatcher.tryNotify();
    }

    void addNode(XdsType type, String resource, ResourceNode<?> node) {
        if (!storageMap.containsKey(type)) {
            storageMap.put(type, new HashMap<>());
        }
        final Map<String, LinkedHashSet<ResourceNode<?>>> resourceToNodes = storageMap.get(type);
        if (!resourceToNodes.containsKey(resource)) {
            resourceToNodes.put(resource, new LinkedHashSet<>());
        }
        final LinkedHashSet<ResourceNode<?>> resourceNodes = resourceToNodes.get(resource);
        resourceNodes.add(node);
        notifyListeners(type, resource);
    }

    void removeNode(XdsType type, String resource, ResourceNode<?> node) {
        final Map<String, LinkedHashSet<ResourceNode<?>>> resourceNodes = storageMap.get(type);
        if (resourceNodes == null) {
            return;
        }
        final LinkedHashSet<ResourceNode<?>> nodes = resourceNodes.get(resource);
        if (nodes == null) {
            return;
        }
        nodes.remove(node);
        if (nodes.isEmpty()) {
            resourceNodes.remove(resource);
            if (resourceNodes.isEmpty()) {
                storageMap.remove(type);
            }
        }
        notifyListeners(type, resource);
    }

    void addWatcher(XdsType type, String resource, ResourceWatcher<ResourceHolder<?>> watcher) {
        if (!watchers.containsKey(type)) {
            watchers.put(type, new HashMap<>());
        }
        final Map<String, CompositeWatcher> resourceToWatchers = watchers.get(type);
        if (!resourceToWatchers.containsKey(resource)) {
            resourceToWatchers.put(resource, new CompositeWatcher(type, resource));
        }
        final CompositeWatcher compositeWatcher = resourceToWatchers.get(resource);
        compositeWatcher.addListener(watcher);
    }

    void removeWatcher(XdsType type, String resource, ResourceWatcher<ResourceHolder<?>> watcher) {
        if (!watchers.containsKey(type)) {
            return;
        }
        final Map<String, CompositeWatcher> resourceToWatchers = watchers.get(type);
        if (!resourceToWatchers.containsKey(resource)) {
            return;
        }
        final CompositeWatcher compositeWatcher = resourceToWatchers.get(resource);
        compositeWatcher.removeListener(watcher);

        if (compositeWatcher.childListeners().isEmpty()) {
            resourceToWatchers.remove(resource);
            if (resourceToWatchers.isEmpty()) {
                watchers.remove(type);
            }
        }
    }

    class CompositeWatcher {
        private final Set<ResourceWatcher<ResourceHolder<?>>> childListeners =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final XdsType type;
        private final String resourceName;
        @Nullable
        private ResourceHolder<?> current;
        // Always pass the first event just in case the initial event is an absent event
        boolean initialized;

        CompositeWatcher(XdsType type, String resourceName) {
            this.type = type;
            this.resourceName = resourceName;
        }

        void tryNotify() {
            final Object candidate = current(type, resourceName);
            if (candidate == NOOP) {
                // none of the watchers are initialized yet
                return;
            }
            if (initialized && Objects.equals(current, candidate)) {
                return;
            }
            initialized = true;
            current = (ResourceHolder<?>) candidate;
            for (ResourceWatcher<ResourceHolder<?>> watcher: childListeners) {
                if (current != null) {
                    try {
                        watcher.onChanged(current);
                    } catch (Exception e) {
                        logger.warn("Unexpected exception while invoking " +
                                    "'ResourceListener.onChanged' for {}", watcher, e);
                    }
                } else {
                    try {
                        watcher.onResourceDoesNotExist(type, resourceName);
                    } catch (Exception e) {
                        logger.warn("Unexpected exception while invoking " +
                                    "'ResourceListener.onResourceDoesNotExist' for {}", watcher, e);
                    }
                }
            }
        }

        void addListener(ResourceWatcher<ResourceHolder<?>> watcher) {
            childListeners.add(watcher);
            if (current != null) {
                try {
                    watcher.onChanged(current);
                } catch (Exception e) {
                    logger.warn("Unexpected exception while invoking " +
                                "'ResourceListener.onChanged' for {}", watcher, e);
                }
            }
        }

        void removeListener(ResourceWatcher<ResourceHolder<?>> watcher) {
            childListeners.remove(watcher);
        }

        Set<ResourceWatcher<ResourceHolder<?>>> childListeners() {
            return childListeners;
        }
    }

    void clearWatchers() {
        watchers.clear();
    }
}

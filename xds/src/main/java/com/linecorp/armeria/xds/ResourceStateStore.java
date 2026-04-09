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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;

final class ResourceStateStore {

    private final Map<XdsType, Map<String, ResourceState>> states = new EnumMap<>(XdsType.class);

    @Nullable
    ResourceState state(XdsType type, String resourceName) {
        final Map<String, ResourceState> perType = states.get(type);
        if (perType == null) {
            return null;
        }
        return perType.get(resourceName);
    }

    ImmutableSet<String> activeResources(XdsType type) {
        final Map<String, ResourceState> perType = states.get(type);
        if (perType == null) {
            return ImmutableSet.of();
        }
        final ImmutableSet.Builder<String> names = ImmutableSet.builder();
        for (Map.Entry<String, ResourceState> entry : perType.entrySet()) {
            if (entry.getValue().status != ResourceStatus.ABSENT) {
                names.add(entry.getKey());
            }
        }
        return names.build();
    }

    ImmutableMap<String, String> resourceVersions(XdsType type) {
        final Map<String, ResourceState> perType = states.get(type);
        if (perType == null) {
            return ImmutableMap.of();
        }
        final ImmutableMap.Builder<String, String> versions = ImmutableMap.builder();
        for (Map.Entry<String, ResourceState> entry : perType.entrySet()) {
            final ResourceState state = entry.getValue();
            if (state.status == ResourceStatus.VERSIONED && state.resource != null) {
                versions.put(entry.getKey(), state.resource.version());
            }
        }
        return versions.build();
    }

    void putWaiting(XdsType type, String resourceName) {
        statesFor(type).put(resourceName, ResourceState.waiting());
    }

    @Nullable
    XdsResource putVersioned(XdsType type, String resourceName, XdsResource resource) {
        final ResourceState prev = state(type, resourceName);
        if (isDuplicateEntry(resource, prev)) {
            return null;
        }
        final long revision = prev != null ? prev.revision + 1 : 1;
        final XdsResource revised = resource instanceof AbstractXdsResource ?
                                    ((AbstractXdsResource) resource).withRevision(revision) : resource;
        statesFor(type).put(resourceName, new ResourceState(ResourceStatus.VERSIONED, revised, revision));
        return revised;
    }

    private boolean isDuplicateEntry(XdsResource resource, @Nullable ResourceState prev) {
        return prev != null && prev.status == ResourceStatus.VERSIONED && prev.resource != null &&
               Objects.equals(prev.resource.version(), resource.version()) &&
               prev.resource.resource().equals(resource.resource());
    }

    boolean putAbsent(XdsType type, String resourceName) {
        final ResourceState prev = state(type, resourceName);
        if (prev != null && prev.status == ResourceStatus.ABSENT) {
            return false;
        }
        statesFor(type).put(resourceName, ResourceState.absent());
        return true;
    }

    void removeIfWaiting(XdsType type, String resourceName) {
        final Map<String, ResourceState> perType = states.get(type);
        if (perType == null) {
            return;
        }
        final ResourceState state = perType.get(resourceName);
        if (state == null || state.status != ResourceStatus.WAITING_FOR_SERVER) {
            return;
        }
        perType.remove(resourceName);
        if (perType.isEmpty()) {
            states.remove(type);
        }
    }

    void remove(XdsType type, String resourceName) {
        final Map<String, ResourceState> perType = states.get(type);
        if (perType == null) {
            return;
        }
        perType.remove(resourceName);
        if (perType.isEmpty()) {
            states.remove(type);
        }
    }

    private Map<String, ResourceState> statesFor(XdsType type) {
        return states.computeIfAbsent(type, key -> new HashMap<>());
    }

    enum ResourceStatus {
        WAITING_FOR_SERVER,
        VERSIONED,
        ABSENT
    }

    static final class ResourceState {
        private final ResourceStatus status;
        @Nullable
        private final XdsResource resource;
        private final long revision;

        private ResourceState(ResourceStatus status, @Nullable XdsResource resource, long revision) {
            this.status = status;
            this.resource = resource;
            this.revision = revision;
        }

        ResourceStatus status() {
            return status;
        }

        @Nullable
        XdsResource resource() {
            return resource;
        }

        private static ResourceState waiting() {
            return new ResourceState(ResourceStatus.WAITING_FOR_SERVER, null, 0);
        }

        private static ResourceState absent() {
            return new ResourceState(ResourceStatus.ABSENT, null, 0);
        }
    }
}

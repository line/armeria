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
    XdsResource resource(XdsType type, String resourceName) {
        final Map<String, ResourceState> perType = states.get(type);
        if (perType == null) {
            return null;
        }
        final ResourceState state = perType.get(resourceName);
        return state != null ? state.resource : null;
    }

    ImmutableSet<String> activeResources(XdsType type) {
        final Map<String, ResourceState> perType = states.get(type);
        if (perType == null) {
            return ImmutableSet.of();
        }
        return ImmutableSet.copyOf(perType.keySet());
    }

    ImmutableMap<String, String> resourceVersions(XdsType type) {
        final Map<String, ResourceState> perType = states.get(type);
        if (perType == null) {
            return ImmutableMap.of();
        }
        final ImmutableMap.Builder<String, String> versions = ImmutableMap.builder();
        for (Map.Entry<String, ResourceState> entry : perType.entrySet()) {
            versions.put(entry.getKey(), entry.getValue().resource.version());
        }
        return versions.build();
    }

    @Nullable
    XdsResource put(XdsType type, String resourceName, XdsResource resource) {
        final Map<String, ResourceState> perType = states.get(type);
        final ResourceState prev = perType != null ? perType.get(resourceName) : null;
        if (isDuplicateEntry(resource, prev)) {
            return null;
        }
        final long revision = prev != null ? prev.revision + 1 : 1;
        final XdsResource revised = resource instanceof AbstractXdsResource ?
                                    ((AbstractXdsResource) resource).withRevision(revision) : resource;
        statesFor(type).put(resourceName, new ResourceState(revised, revision));
        return revised;
    }

    private static boolean isDuplicateEntry(XdsResource resource, @Nullable ResourceState prev) {
        return prev != null &&
               Objects.equals(prev.resource.version(), resource.version()) &&
               prev.resource.resource().equals(resource.resource());
    }

    boolean remove(XdsType type, String resourceName) {
        final Map<String, ResourceState> perType = states.get(type);
        if (perType == null) {
            return false;
        }
        final boolean removed = perType.remove(resourceName) != null;
        if (perType.isEmpty()) {
            states.remove(type);
        }
        return removed;
    }

    private Map<String, ResourceState> statesFor(XdsType type) {
        return states.computeIfAbsent(type, key -> new HashMap<>());
    }

    static final class ResourceState {
        final XdsResource resource;
        final long revision;

        ResourceState(XdsResource resource, long revision) {
            this.resource = resource;
            this.revision = revision;
        }
    }
}

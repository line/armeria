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

package com.linecorp.armeria.xds.configsource;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.XdsType;

/**
 * Represents the set of xDS resource names that are currently subscribed for a given
 * {@link XdsType}. Emitted by the interest stream whenever subscriptions change.
 */
@UnstableApi
public final class InterestedResources {

    private final XdsType type;
    private final Set<String> resourceNames;

    InterestedResources(XdsType type, Set<String> resourceNames) {
        this.type = requireNonNull(type, "type");
        this.resourceNames = ImmutableSet.copyOf(requireNonNull(resourceNames, "resourceNames"));
    }

    /**
     * Returns the {@link XdsType} of the interested resources.
     */
    public XdsType type() {
        return type;
    }

    /**
     * Returns the set of resource names currently subscribed.
     */
    public Set<String> resourceNames() {
        return resourceNames;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("type", type)
                          .add("resourceNames", resourceNames)
                          .toString();
    }
}

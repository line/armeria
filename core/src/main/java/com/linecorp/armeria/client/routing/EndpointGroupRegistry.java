/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.routing;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.linecorp.armeria.client.Endpoint;

/**
 * An in-memory registry of server groups.
 */
public final class EndpointGroupRegistry {
    private static final Map<String, EndpointSelector> serverGroups = new ConcurrentHashMap<>();

    /**
     * Register the specified {@link EndpointGroup}.
     *
     * @throws EndpointGroupException if {@code groupName} has already been registered.
     */
    public static void register(String groupName, EndpointGroup endpointGroup,
                                EndpointSelectionStrategy endpointSelectionStrategy) {
        requireNonNull(groupName, "groupName");
        requireNonNull(endpointGroup, "group");
        requireNonNull(endpointSelectionStrategy, "endpointSelectionStrategy");

        if (serverGroups.putIfAbsent(groupName, endpointSelectionStrategy.newSelector(endpointGroup)) != null) {
            throw new EndpointGroupException("A EndpointGroup with the same name exists: " + groupName);
        }
    }

    /**
     * Replaces an existing {@link EndpointGroup}.
     *
     * @throws EndpointGroupException if {@code groupName} not registered yet.
     */
    public static void replace(String groupName, EndpointGroup endpointGroup,
                               EndpointSelectionStrategy endpointSelectionStrategy) {
        requireNonNull(groupName, "groupName");
        requireNonNull(endpointGroup, "group");
        requireNonNull(endpointSelectionStrategy, "endpointSelectionStrategy");

        if (serverGroups.replace(groupName, endpointSelectionStrategy.newSelector(endpointGroup)) == null) {
            throw new EndpointGroupException("non-existent EndpointGroup: " + groupName);
        }
    }

    /**
     * Returns the {@link EndpointSelector} for the specified {@code groupName}.
     *
     * @return the {@link EndpointSelector}, or {@code null} if {@code groupName} has not been registered yet.
     */
    public static EndpointSelector getNodeSelector(String groupName) {
        requireNonNull(groupName, "groupName");

        return serverGroups.get(groupName);
    }

    /**
     * Get the {@link EndpointGroup} for the specified {@code groupName}.
     *
     * @return the {@link EndpointSelector}, or {@code null} if {@code groupName} has not been registered yet.
     */
    public static EndpointGroup get(String groupName) {
        requireNonNull(groupName, "groupName");

        EndpointSelector endpointSelector = serverGroups.get(groupName);
        if (endpointSelector == null) {
            return null;
        }
        return endpointSelector.group();
    }

    /**
     * Selects an {@link Endpoint} from the {@link EndpointGroup} associated with the specified
     * {@code groupName}.
     */
    public static Endpoint selectNode(String groupName) {
        EndpointSelector endpointSelector = getNodeSelector(groupName);
        if (endpointSelector == null) {
            throw new EndpointGroupException("non-existent EndpointGroup: " + groupName);
        }

        return endpointSelector.select();
    }

    private EndpointGroupRegistry() {}
}

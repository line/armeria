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
     * Registers the specified {@link EndpointGroup}. If there's already an {@link EndpointGroup} with the
     * specified {@code groupName}, this method will replace it with the new one.
     *
     * @return {@code true} if there was no {@link EndpointGroup} with the specified {@code groupName}.
     *         {@code false} if there was already an {@link EndpointGroup} with the specified {@code groupName}
     *         and it has been replaced with the new one.
     */
    public static boolean register(String groupName, EndpointGroup endpointGroup,
                                   EndpointSelectionStrategy endpointSelectionStrategy) {
        requireNonNull(groupName, "groupName");
        requireNonNull(endpointGroup, "group");
        requireNonNull(endpointSelectionStrategy, "endpointSelectionStrategy");

        final EndpointSelector oldSelector = serverGroups.put(
                groupName, endpointSelectionStrategy.newSelector(endpointGroup));

        return oldSelector == null;
    }

    /**
     * Unregisters the {@link EndpointGroup} with the specified {@code groupName}. Note that this is
     * potentially a dangerous operation; make sure the {@code groupName} of the unregistered
     * {@link EndpointGroup} is not in use by any clients.
     *
     * @return {@code true} if the {@link EndpointGroup} with the specified {@code groupName} has been removed.
     *         {@code false} if there's no such {@link EndpointGroup} in the registry.
     */
    public static boolean unregister(String groupName) {
        requireNonNull(groupName, "groupName");
        return serverGroups.remove(groupName) != null;
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

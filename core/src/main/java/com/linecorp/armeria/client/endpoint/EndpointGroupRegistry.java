/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.google.common.base.Ascii;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;

/**
 * An in-memory registry of server groups.
 */
public final class EndpointGroupRegistry {

    private static final Pattern GROUP_NAME_PATTERN = Pattern.compile("^[-_.0-9a-z]+$");
    private static final Map<String, EndpointSelector> serverGroups = new ConcurrentHashMap<>();

    /**
     * Registers the specified {@link EndpointGroup}. If there's already an {@link EndpointGroup} with the
     * specified {@code groupName}, this method will replace it with the new one.
     *
     * @param groupName the case-insensitive name of the {@link EndpointGroup} that matches
     *                  the regular expression {@code /^[-_.0-9a-zA-Z]+$/}
     * @param endpointGroup the {@link EndpointGroup} to register
     * @param endpointSelectionStrategy the {@link EndpointSelectionStrategy} of the registered group
     *
     * @return {@code true} if there was no {@link EndpointGroup} with the specified {@code groupName}.
     *         {@code false} if there was already an {@link EndpointGroup} with the specified {@code groupName}
     *         and it has been replaced with the new one.
     */
    public static boolean register(String groupName, EndpointGroup endpointGroup,
                                   EndpointSelectionStrategy endpointSelectionStrategy) {
        groupName = normalizeGroupName(groupName);
        if (!GROUP_NAME_PATTERN.matcher(groupName).matches()) {
            throw new IllegalArgumentException(
                    "groupName: " + groupName + " (expected: " + GROUP_NAME_PATTERN.pattern() + ')');
        }

        requireNonNull(endpointGroup, "group");
        requireNonNull(endpointSelectionStrategy, "endpointSelectionStrategy");

        final EndpointSelector oldSelector = serverGroups.put(
                groupName, endpointSelectionStrategy.newSelector(endpointGroup));

        return oldSelector == null;
    }

    /**
     * Unregisters the {@link EndpointGroup} with the specified case-insensitive {@code groupName}.
     * Note that this is potentially a dangerous operation; make sure the {@code groupName} of the unregistered
     * {@link EndpointGroup} is not in use by any clients.
     *
     * @return {@code true} if the {@link EndpointGroup} with the specified {@code groupName} has been removed.
     *         {@code false} if there's no such {@link EndpointGroup} in the registry.
     */
    public static boolean unregister(String groupName) {
        groupName = normalizeGroupName(groupName);
        return serverGroups.remove(groupName) != null;
    }

    /**
     * Returns the {@link EndpointSelector} for the specified case-insensitive {@code groupName}.
     *
     * @return the {@link EndpointSelector}, or {@code null} if {@code groupName} has not been registered yet.
     */
    public static EndpointSelector getNodeSelector(String groupName) {
        groupName = normalizeGroupName(groupName);
        return serverGroups.get(groupName);
    }

    /**
     * Get the {@link EndpointGroup} for the specified case-insensitive {@code groupName}.
     *
     * @return the {@link EndpointSelector}, or {@code null} if {@code groupName} has not been registered yet.
     */
    public static EndpointGroup get(String groupName) {
        groupName = normalizeGroupName(groupName);
        EndpointSelector endpointSelector = serverGroups.get(groupName);
        if (endpointSelector == null) {
            return null;
        }
        return endpointSelector.group();
    }

    /**
     * Selects an {@link Endpoint} from the {@link EndpointGroup} associated with the specified
     * {@link ClientRequestContext} and case-insensitive {@code groupName}.
     */
    public static Endpoint selectNode(ClientRequestContext ctx, String groupName) {
        groupName = normalizeGroupName(groupName);
        EndpointSelector endpointSelector = getNodeSelector(groupName);
        if (endpointSelector == null) {
            throw new EndpointGroupException("non-existent EndpointGroup: " + groupName);
        }

        return endpointSelector.select(ctx);
    }

    private static String normalizeGroupName(String groupName) {
        return Ascii.toLowerCase(requireNonNull(groupName, "groupName"));
    }

    private EndpointGroupRegistry() {}
}

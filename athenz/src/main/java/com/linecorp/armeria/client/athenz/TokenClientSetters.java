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

package com.linecorp.armeria.client.athenz;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.google.common.collect.ImmutableList;

/**
 * A set of setter methods for configuring a Athenz token client builder.
 */
public interface TokenClientSetters<SELF extends TokenClientSetters<SELF>> {

    /**
     * Sets the Athenz domain name.
     * The domain name must be set before building the client.
     */
    SELF domainName(String domainName);

    /**
     * Adds Athenz role names.
     */
    default SELF roleNames(String... roleNames) {
        requireNonNull(roleNames, "roleNames");
        return roleNames(ImmutableList.copyOf(roleNames));
    }

    /**
     * Adds Athenz role names.
     */
    SELF roleNames(Iterable<String> roleNames);

    /**
     * Sets the duration before the token expires to refresh it.
     * If not set, the default is 10 minutes.
     */
    SELF refreshBefore(Duration refreshBefore);

    /**
     * Sets whether to acquire an Athenz token before the first request is made.
     * If {@code true}, the client will attempt to acquire an Athenz token as soon as the client is created.
     * This can help reduce latency for the first request. However, it may lead to unnecessary token acquisition
     * if the client is not used immediately.
     *
     * <p>If not set, the default is {@code false}.
     */
    SELF preload(boolean preload);
}

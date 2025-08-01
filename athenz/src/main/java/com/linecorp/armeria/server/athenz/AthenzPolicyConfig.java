/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.server.athenz;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A configuration class to download and refresh the Athenz policy data.
 */
@UnstableApi
public final class AthenzPolicyConfig {

    private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofHours(1);

    private final List<String> domains;
    private final Map<String, String> policyVersions;
    private final boolean jwsPolicySupport;
    private final Duration refreshInterval;

    /**
     * Creates a new {@link AthenzPolicyConfig} with the specified domain.
     */
    public AthenzPolicyConfig(String domain) {
        this(ImmutableList.of(requireNonNull(domain, "domain")), ImmutableMap.of(), true,
             DEFAULT_REFRESH_INTERVAL);
    }

    /**
     * Creates a new {@link AthenzPolicyConfig} with the specified domains, policy versions,
     * and JWS policy support.
     *
     * @param domains the list of domains
     * @param policyVersions the map of policy versions
     * @param jwsPolicySupport whether JWS policy support is enabled
     * @param refreshInterval the interval for refreshing the policy data
     */
    public AthenzPolicyConfig(List<String> domains, Map<String, String> policyVersions,
                              boolean jwsPolicySupport, Duration refreshInterval) {
        requireNonNull(domains, "domains");
        checkArgument(!domains.isEmpty(), "domains must not be empty");
        requireNonNull(policyVersions, "policyVersions");
        requireNonNull(refreshInterval, "refreshInterval");
        checkArgument(refreshInterval.toMillis() > 0, "refreshInterval must be greater than 0");

        this.domains = domains;
        this.policyVersions = policyVersions;
        this.jwsPolicySupport = jwsPolicySupport;
        this.refreshInterval = refreshInterval;
    }

    /**
     * Returns the list of domains for which the ZPU configuration is applicable.
     */
    public List<String> domains() {
        return domains;
    }

    /**
     * Returns the map of policy versions.
     */
    public Map<String, String> policyVersions() {
        return policyVersions;
    }

    /**
     * Returns whether JWS policy support is enabled.
     */
    public boolean jwsPolicySupport() {
        return jwsPolicySupport;
    }

    /**
     * Returns the interval for refreshing the policy data.
     */
    public Duration refreshInterval() {
        return refreshInterval;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AthenzPolicyConfig)) {
            return false;
        }
        final AthenzPolicyConfig zpuConfig = (AthenzPolicyConfig) o;
        return jwsPolicySupport == zpuConfig.jwsPolicySupport &&
               domains.equals(zpuConfig.domains) &&
               policyVersions.equals(zpuConfig.policyVersions) &&
               refreshInterval.equals(zpuConfig.refreshInterval);
    }

    @Override
    public int hashCode() {
        return Objects.hash(domains, policyVersions, jwsPolicySupport, refreshInterval);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("domains", domains)
                          .add("policyVersions", policyVersions)
                          .add("jwsPolicySupport", jwsPolicySupport)
                          .add("refreshInterval", refreshInterval)
                          .toString();
    }
}

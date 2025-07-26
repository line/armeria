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

package com.linecorp.armeria.server.athenz;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.yahoo.athenz.zpe.ZpeClient;
import com.yahoo.athenz.zpe.pkey.PublicKeyStore;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A base builder for creating an Athenz service that checks access permissions using Athenz policies.
 */
@UnstableApi
public abstract class AbstractAthenzServiceBuilder<SELF extends AbstractAthenzServiceBuilder<SELF>> {

    private static final Duration DEFAULT_OAUTH2_KEYS_REFRESH_INTERVAL = Duration.ofHours(1);
    private static final int MAX_TOKEN_CACHE_SIZE = 10240;

    private Duration oauth2KeysRefreshInterval = DEFAULT_OAUTH2_KEYS_REFRESH_INTERVAL;

    private final ZtsBaseClient ztsBaseClient;
    @Nullable
    private AthenzPolicyConfig policyConfig;
    private int maxTokenCacheSize = MAX_TOKEN_CACHE_SIZE;

    AbstractAthenzServiceBuilder(ZtsBaseClient ztsBaseClient) {
        this.ztsBaseClient = ztsBaseClient;
    }

    /**
     * Sets the {@link AthenzPolicyConfig} to fetch Athenz policies from the ZTS server.
     *
     * <p><strong>Mandatory:</strong> This field must be set before building the service.
     */
    public SELF policyConfig(AthenzPolicyConfig policyConfig) {
        requireNonNull(policyConfig, "policyConfig");
        this.policyConfig = policyConfig;
        return self();
    }

    /**
     * Sets the interval for refreshing OAuth2 keys from the ZTS server.
     * If not set, defaults to 1 hour.
     */
    public SELF oauth2KeysRefreshInterval(Duration oauth2KeysRefreshInterval) {
        requireNonNull(oauth2KeysRefreshInterval, "oauth2KeysRefreshInterval");
        this.oauth2KeysRefreshInterval = oauth2KeysRefreshInterval;
        return self();
    }

    /**
     * Set the limit of role and access tokens that are cached to improve the performance of validating
     * signatures since the tokens must be re-used by clients until they're about to be expired.
     * If not set, defaults to {@value MAX_TOKEN_CACHE_SIZE}.
     */
    public SELF maxTokenCacheSize(int maxTokenCacheSize) {
        checkArgument(maxTokenCacheSize > 0, "maxTokenCacheSize: %s (expected > 0)", maxTokenCacheSize);
        this.maxTokenCacheSize = maxTokenCacheSize;
        return self();
    }

    MinifiedAuthZpeClient buildAuthZpeClient() {
        checkState(policyConfig != null, "policyConfig is not set");
        requireNonNull(policyConfig, "policyConfig must be set before building the service");
        final PublicKeyStore publicKeyStore = new AthenzPublicKeyProvider(ztsBaseClient,
                                                                          oauth2KeysRefreshInterval);
        final ZpeClient zpeClient = new AthenzPolicyClient(ztsBaseClient, policyConfig, publicKeyStore,
                                                           maxTokenCacheSize);
        // NB: zpeClient.init() will block until the initial policy data is loaded.
        zpeClient.init(null);
        return new MinifiedAuthZpeClient(ztsBaseClient, publicKeyStore, zpeClient);
    }

    @SuppressWarnings("unchecked")
    private SELF self() {
        return (SELF) this;
    }
}

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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for creating an {@link AthenzTokenClient}.
 */
@UnstableApi
public final class AthenzTokenClientBuilder implements TokenClientSetters<AthenzTokenClientBuilder> {

    private static final Duration DEFAULT_REFRESH_BEFORE = Duration.ofMinutes(10);

    private final ZtsBaseClient ztsBaseClient;

    @Nullable
    private String domainName;
    private final ImmutableList.Builder<String> roleNamesBuilder = ImmutableList.builder();
    private Duration refreshBefore = DEFAULT_REFRESH_BEFORE;
    private boolean preload;
    private boolean roleToken;

    AthenzTokenClientBuilder(ZtsBaseClient ztsBaseClient) {
        this.ztsBaseClient = ztsBaseClient;
    }

    /**
     * Sets the Athenz domain name.
     * The domain name must be set before calling {@link #build()}.
     */
    @Override
    public AthenzTokenClientBuilder domainName(String domainName) {
        this.domainName = requireNonNull(domainName, "domainName");
        return this;
    }

    @Override
    public AthenzTokenClientBuilder roleNames(Iterable<String> roleNames) {
        requireNonNull(roleNames, "roleNames");
        roleNamesBuilder.addAll(roleNames);
        return this;
    }

    @Override
    public AthenzTokenClientBuilder refreshBefore(Duration refreshBefore) {
        requireNonNull(refreshBefore, "refreshBefore");
        checkState(!refreshBefore.isNegative(), "refreshBefore: %s (expected: >= 0)", refreshBefore);
        this.refreshBefore = refreshBefore;
        return this;
    }

    @Override
    public AthenzTokenClientBuilder preload(boolean preload) {
        this.preload = preload;
        return this;
    }

    /**
     * Sets whether the token to be fetched is a role token. If {@code true}, a role token will be fetched
     * and if {@code false}, an access token will be fetched. The default value is {@code false}.
     */
    public AthenzTokenClientBuilder roleToken(boolean roleToken) {
        this.roleToken = roleToken;
        return this;
    }

    /**
     * Creates a new {@link AthenzTokenClient} based on the properties set so far.
     *
     * @throws IllegalStateException if the required properties are not set.
     */
    public AthenzTokenClient build() {
        checkState(domainName != null, "domainName must be set");
        final List<String> roleNames = roleNamesBuilder.build();
        if (roleToken) {
            return new RoleTokenClient(ztsBaseClient, domainName, roleNames, refreshBefore, preload);
        } else {
            return new AccessTokenClient(ztsBaseClient, domainName, roleNames, refreshBefore, preload);
        }
    }
}

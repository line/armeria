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

package com.linecorp.armeria.client.athenz;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.athenz.AthenzTokenHeader;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

/**
 * A builder for creating an {@link AthenzClient} decorator.
 *
 * <p>Example:
 * <pre>{@code
 * ZtsBaseClient ztsBaseClient = ...;
 *
 * AthenzClient.builder(ztsBaseClient)
 *             .domainName("my-domain")
 *             .roleNames("my-role")
 *             .tokenHeader(TokenType.ACCESS_TOKEN)
 *             .refreshBefore(Duration.ofMinutes(5))
 *             .newDecorator();
 * }</pre>
 *
 * <p>For custom header implementation, see {@link AthenzTokenHeader}.
 */
@UnstableApi
public final class AthenzClientBuilder {

    private static final Duration DEFAULT_REFRESH_BEFORE = Duration.ofMinutes(10);
    private static final MeterIdPrefix DEFAULT_METER_ID_PREFIX =
            new MeterIdPrefix("armeria.client.athenz");

    private final ZtsBaseClient ztsBaseClient;
    @Nullable
    private String domainName;
    private final ImmutableList.Builder<String> roleNamesBuilder = ImmutableList.builder();
    private AthenzTokenHeader tokenHeader = TokenType.ACCESS_TOKEN;
    private Duration refreshBefore = DEFAULT_REFRESH_BEFORE;
    private MeterIdPrefix meterIdPrefix = DEFAULT_METER_ID_PREFIX;

    AthenzClientBuilder(ZtsBaseClient ztsBaseClient) {
        this.ztsBaseClient = requireNonNull(ztsBaseClient, "ztsBaseClient");
    }

    /**
     * Sets the Athenz domain name.
     * The domain name must be set before calling {@link #newDecorator()}.
     */
    public AthenzClientBuilder domainName(String domainName) {
        this.domainName = requireNonNull(domainName, "domainName");
        return this;
    }

    /**
     * Adds Athenz role names.
     */
    public AthenzClientBuilder roleNames(String... roleNames) {
        requireNonNull(roleNames, "roleNames");
        roleNamesBuilder.add(roleNames);
        return this;
    }

    /**
     * Adds Athenz role names.
     */
    public AthenzClientBuilder roleNames(Iterable<String> roleNames) {
        requireNonNull(roleNames, "roleNames");
        roleNamesBuilder.addAll(roleNames);
        return this;
    }

    /**
     * Sets the type of Athenz token to obtain.
     * If not set, the default is {@link TokenType#ACCESS_TOKEN}.
     *
     * @deprecated Use {@link #tokenHeader(AthenzTokenHeader)} instead.
     */
    @Deprecated
    public AthenzClientBuilder tokenType(TokenType tokenType) {
        return tokenHeader(tokenType);
    }

    /**
     * Sets the HTTP header to use for the Athenz token.
     * If not set, the default is {@link TokenType#ACCESS_TOKEN}.
     *
     * <p>This method allows you to specify either a predefined {@link TokenType} or a custom
     * {@link AthenzTokenHeader} implementation for flexible header configuration.
     *
     * @param tokenHeader the token header configuration
     * @return this builder
     */
    public AthenzClientBuilder tokenHeader(AthenzTokenHeader tokenHeader) {
        this.tokenHeader = requireNonNull(tokenHeader, "tokenHeader");
        return this;
    }

    /**
     * Sets the duration before the token expires to refresh it.
     * If not set, the default is 10 minutes.
     */
    public AthenzClientBuilder refreshBefore(Duration refreshBefore) {
        requireNonNull(refreshBefore, "refreshBefore");
        checkState(!refreshBefore.isNegative(), "refreshBefore: %s (expected: >= 0)", refreshBefore);
        this.refreshBefore = refreshBefore;
        return this;
    }

    /**
     * Sets the prefix to use when naming the metrics for this client.
     * If not set, the default is {@code "armeria.client.athenz"}.
     */
    public AthenzClientBuilder meterIdPrefix(MeterIdPrefix meterIdPrefix) {
        this.meterIdPrefix = requireNonNull(meterIdPrefix, "meterIdPrefix");
        return this;
    }

    /**
     * Returns a new {@link HttpClient} decorator configured with the settings in this builder.
     */
    public Function<? super HttpClient, AthenzClient> newDecorator() {
        final String domainName = this.domainName;
        checkState(domainName != null, "domainName is not set");

        final List<String> roleNames = roleNamesBuilder.build();
        return delegate -> new AthenzClient(delegate, ztsBaseClient, domainName, roleNames,
                                            tokenHeader, refreshBefore, meterIdPrefix);
    }
}


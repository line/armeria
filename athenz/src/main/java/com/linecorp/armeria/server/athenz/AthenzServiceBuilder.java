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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.athenz.AthenzTokenHeader;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.athenz.resource.AthenzResourceProvider;

/**
 * A builder for creating an {@link AthenzService} that checks access permissions using Athenz policies.
 */
@UnstableApi
public final class AthenzServiceBuilder extends AbstractAthenzServiceBuilder<AthenzServiceBuilder> {

    private static final List<AthenzTokenHeader> DEFAULT_TOKEN_HEADERS =
            ImmutableList.copyOf(TokenType.values());

    private List<AthenzTokenHeader> tokenHeaders = DEFAULT_TOKEN_HEADERS;

    private static final String DEFAULT_RESOURCE_TAG_VALUE = "*";

    @Nullable
    private AthenzResourceProvider athenzResourceProvider;

    @Nullable
    private String athenzAction;

    @Nullable
    private String resourceTagValue;

    AthenzServiceBuilder(ZtsBaseClient ztsBaseClient) {
        super(ztsBaseClient);
    }

    /**
     * Sets the Athenz resource to check access permissions against.
     *
     * <p><strong>Mandatory:</strong> Either this or
     * {@link #resourceProvider(AthenzResourceProvider, String)} must be set before calling
     * {@link #newDecorator()}.</p>
     */
    public AthenzServiceBuilder resource(String athenzResource) {
        requireNonNull(athenzResource, "athenzResource");
        checkArgument(!athenzResource.isEmpty(), "athenzResource must not be empty");
        checkState(athenzResourceProvider == null,
                   "resource() and resourceProvider() are mutually exclusive");
        final CompletableFuture<String> resourceFuture = UnmodifiableFuture.completedFuture(athenzResource);
        athenzResourceProvider = (ctx, req) -> resourceFuture;
        resourceTagValue = athenzResource;
        return this;
    }

    /**
     * Sets the {@link AthenzResourceProvider} used to resolve the Athenz resource dynamically for
     * each request.
     *
     * <p><strong>Mandatory:</strong> Either this or {@link #resource(String)} must be set before
     * calling {@link #newDecorator()}.</p>
     *
     * @param athenzResourceProvider the provider that resolves the resource for each request
     */
    public AthenzServiceBuilder resourceProvider(AthenzResourceProvider athenzResourceProvider) {
        return resourceProvider(athenzResourceProvider, DEFAULT_RESOURCE_TAG_VALUE);
    }

    /**
     * Sets the {@link AthenzResourceProvider} used to resolve the Athenz resource dynamically for
     * each request.
     *
     * <p><strong>Mandatory:</strong> Either this or {@link #resource(String)} must be set before
     * calling {@link #newDecorator()}.</p>
     *
     * @param athenzResourceProvider the provider that resolves the resource for each request
     * @param resourceTagValue       a stable tag value for metrics to avoid cardinality explosion
     *                               (e.g., "admin" or "users" instead of dynamic resource values)
     */
    public AthenzServiceBuilder resourceProvider(AthenzResourceProvider athenzResourceProvider,
                                                 String resourceTagValue) {
        requireNonNull(athenzResourceProvider, "resourceProvider");
        requireNonNull(resourceTagValue, "resourceTagValue");
        checkArgument(!resourceTagValue.isEmpty(), "resourceTagValue must not be empty");
        checkState(this.athenzResourceProvider == null,
                   "resource() and resourceProvider() are mutually exclusive");
        this.athenzResourceProvider = athenzResourceProvider;
        this.resourceTagValue = resourceTagValue;
        return this;
    }

    /**
     * Sets the Athenz action to check access permissions against.
     *
     * <p><strong>Mandatory:</strong> This field must be set before calling {@link #newDecorator()}.
     */
    public AthenzServiceBuilder action(String athenzAction) {
        this.athenzAction = athenzAction;
        requireNonNull(athenzAction, "athenzAction");
        checkArgument(!athenzAction.isEmpty(), "athenzAction must not be empty");
        return this;
    }

    /**
     * Sets the {@link TokenType}s to be used for access checks.
     * If not set, all token types are checked by default.
     *
     * @deprecated Use {@link #tokenHeader(AthenzTokenHeader...)} instead.
     */
    @Deprecated
    public AthenzServiceBuilder tokenType(TokenType... tokenTypes) {
        requireNonNull(tokenTypes, "tokenTypes");
        checkArgument(tokenTypes.length > 0, "tokenTypes must not be empty");
        return tokenHeader(tokenTypes);
    }

    /**
     * Sets the {@link TokenType}s to be used for access checks.
     * If not set, all token types are checked by default.
     *
     * @deprecated Use {@link #tokenHeader(Iterable)} instead.
     */
    @Deprecated
    public AthenzServiceBuilder tokenType(Iterable<TokenType> tokenTypes) {
        requireNonNull(tokenTypes, "tokenTypes");
        checkArgument(!Iterables.isEmpty(tokenTypes), "tokenTypes must not be empty");
        return tokenHeader(tokenTypes);
    }

    /**
     * Sets the headers to be used for access checks.
     * If not set, all predefined token types are checked by default.
     *
     * <p>This method allows you to specify either predefined {@link TokenType}s or custom
     * {@link AthenzTokenHeader} implementations for flexible header configuration.
     *
     * @param tokenHeaders the token header configurations
     * @return this builder
     */
    public AthenzServiceBuilder tokenHeader(AthenzTokenHeader... tokenHeaders) {
        requireNonNull(tokenHeaders, "tokenHeaders");
        checkArgument(tokenHeaders.length > 0, "tokenHeaders must not be empty");
        this.tokenHeaders = ImmutableList.copyOf(tokenHeaders);
        return this;
    }

    /**
     * Sets the headers to be used for access checks.
     * If not set, all predefined token types are checked by default.
     *
     * <p>This method allows you to specify either predefined {@link TokenType}s or custom
     * {@link AthenzTokenHeader} implementations for flexible header configuration.
     *
     * @param tokenHeaders the token header configurations
     * @return this builder
     */
    public AthenzServiceBuilder tokenHeader(Iterable<? extends AthenzTokenHeader> tokenHeaders) {
        requireNonNull(tokenHeaders, "tokenHeaders");
        checkArgument(!Iterables.isEmpty(tokenHeaders), "tokenHeaders must not be empty");
        this.tokenHeaders = ImmutableList.copyOf(tokenHeaders);
        return this;
    }

    /**
     * Returns a new {@link HttpClient} decorator that performs access checks using Athenz policies.
     */
    public Function<? super HttpService, AthenzService> newDecorator() {
        final AthenzResourceProvider athenzResourceProvider = this.athenzResourceProvider;
        final String athenzAction = this.athenzAction;
        final List<AthenzTokenHeader> tokenHeaders = this.tokenHeaders;
        final String resourceTagValue = this.resourceTagValue;

        checkState(athenzResourceProvider != null, "resource or resourceProvider is not set");
        checkState(athenzAction != null, "action is not set");
        checkState(resourceTagValue != null, "resourceTagValue is not set");

        final AthenzAuthorizer authorizer = new AthenzAuthorizer(buildAuthZpeClient());
        return delegate -> new AthenzService(
                delegate, authorizer, athenzResourceProvider,
                athenzAction, tokenHeaders, meterIdPrefix(), resourceTagValue);
    }
}

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
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.server.HttpService;

/**
 * A builder for creating an {@link AthenzService} that checks access permissions using Athenz policies.
 */
@UnstableApi
public final class AthenzServiceBuilder extends AbstractAthenzServiceBuilder<AthenzServiceBuilder> {

    private static final List<TokenType> DEFAULT_TOKEN_TYPES = ImmutableList.copyOf(TokenType.values());

    private List<TokenType> tokenTypes = DEFAULT_TOKEN_TYPES;

    @Nullable
    private String athenzResource;
    @Nullable
    private String athenzAction;

    AthenzServiceBuilder(ZtsBaseClient ztsBaseClient) {
        super(ztsBaseClient);
    }

    /**
     * Sets the Athenz resource to check access permissions against.
     *
     * <p><strong>Mandatory:</strong> This field must be set before calling {@link #newDecorator()}.
     */
    public AthenzServiceBuilder resource(String athenzResource) {
        requireNonNull(athenzResource, "athenzResource");
        checkArgument(!athenzResource.isEmpty(), "athenzResource must not be empty");
        this.athenzResource = athenzResource;
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
     */
    public AthenzServiceBuilder tokenType(TokenType... tokenTypes) {
        requireNonNull(tokenTypes, "tokenTypes");
        checkArgument(tokenTypes.length > 0, "tokenTypes must not be empty");
        return this;
    }

    /**
     * Sets the {@link TokenType}s to be used for access checks.
     * If not set, all token types are checked by default.
     */
    public AthenzServiceBuilder tokenType(Iterable<TokenType> tokenTypes) {
        requireNonNull(tokenTypes, "tokenTypes");
        checkArgument(!Iterables.isEmpty(tokenTypes), "tokenTypes must not be empty");
        this.tokenTypes = ImmutableList.copyOf(tokenTypes);
        return this;
    }

    /**
     * Returns a new {@link HttpClient} decorator that performs access checks using Athenz policies.
     */
    public Function<? super HttpService, AthenzService> newDecorator() {
        final String athenzResource = this.athenzResource;
        final String athenzAction = this.athenzAction;
        final List<TokenType> tokenTypes = this.tokenTypes;

        checkState(athenzResource != null, "resource is not set");
        checkState(athenzAction != null, "action is not set");

        final MinifiedAuthZpeClient authZpeClient = buildAuthZpeClient();
        return delegate -> new AthenzService(delegate, authZpeClient,
                                             athenzResource, athenzAction, tokenTypes);
    }
}

/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client.auth.oauth2;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.common.auth.oauth2.OAuth2ResponseHandler;

/**
 * A builder for {@link OAuth2AuthorizationGrant} which represents an OAuth 2.0 Access Token Grant flow.
 */
@UnstableApi
public final class OAuth2AuthorizationGrantBuilder {

    /**
     * A period when the token should be refreshed proactively prior to its expiry.
     */
    private static final Duration DEFAULT_REFRESH_BEFORE = Duration.ofMinutes(1L); // 1 minute

    private final WebClient accessTokenEndpoint;
    private final String accessTokenEndpointPath;

    @Nullable
    private Supplier<AccessTokenRequest> requestSupplier;
    private OAuth2ResponseHandler<GrantedOAuth2AccessToken> responseHandler =
            DefaultAccessTokenResponseHandler.INSTANCE;
    private Duration refreshBefore = DEFAULT_REFRESH_BEFORE;

    @Nullable
    private Supplier<CompletableFuture<? extends GrantedOAuth2AccessToken>> fallbackTokenProvider;
    @Nullable
    private Consumer<? super GrantedOAuth2AccessToken> newTokenConsumer;

    OAuth2AuthorizationGrantBuilder(WebClient accessTokenEndpoint, String accessTokenEndpointPath) {
        this.accessTokenEndpoint = requireNonNull(accessTokenEndpoint, "accessTokenEndpoint");
        this.accessTokenEndpointPath = requireNonNull(accessTokenEndpointPath, "accessTokenEndpointPath");
    }

    /**
     * Sets the {@link AccessTokenRequest} to be used when requesting an access token.
     *
     * <p>Either an {@link AccessTokenRequest} or the {@link Supplier} of an {@link AccessTokenRequest}
     * must be set before building an {@link OAuth2AuthorizationGrant}.
     */
    public OAuth2AuthorizationGrantBuilder accessTokenRequest(AccessTokenRequest accessTokenRequest) {
        requireNonNull(accessTokenRequest, "accessTokenRequest");
        return accessTokenRequest(() -> accessTokenRequest);
    }

    /**
     * Sets the {@link Supplier} of {@link AccessTokenRequest} to be used when requesting an access token.
     * The supplier will be invoked every time when an access token is requested.
     *
     * <p>Either an {@link AccessTokenRequest} or the {@link Supplier} of an {@link AccessTokenRequest}
     * must be set before building an {@link OAuth2AuthorizationGrant}.
     */
    public OAuth2AuthorizationGrantBuilder accessTokenRequest(
            Supplier<AccessTokenRequest> accessTokenRequestSupplier) {
        requireNonNull(accessTokenRequestSupplier, "accessTokenRequestSupplier");
        requestSupplier = accessTokenRequestSupplier;
        return this;
    }

    /**
     * Sets a custom {@link OAuth2ResponseHandler} to handle the response of an access token request.
     * If not set, the default response handler will be used.
     */
    public OAuth2AuthorizationGrantBuilder responseHandler(
            OAuth2ResponseHandler<GrantedOAuth2AccessToken> responseHandler) {
        this.responseHandler = requireNonNull(responseHandler, "responseHandler");
        return this;
    }

    /**
     * Sets a period when the token should be refreshed proactively prior to its expiry.
     */
    public OAuth2AuthorizationGrantBuilder refreshBefore(Duration refreshBefore) {
        this.refreshBefore = requireNonNull(refreshBefore, "refreshBefore");
        return this;
    }

    /**
     * Sets an optional {@link Supplier} to acquire an access token before requesting it to the authorization
     * server. If the provided {@link GrantedOAuth2AccessToken} is valid, the client doesn't request a new
     * token.
     *
     * <p>This is supposed to be used with {@link #newTokenConsumer(Consumer)} and gets executed
     * in the following cases:
     * <ul>
     *     <li>Before the first attempt to acquire an access token.</li>
     *     <li>Before a subsequent attempt after token issue or refresh failure.</li>
     * </ul>
     *
     * @see #newTokenConsumer(Consumer)
     */
    public OAuth2AuthorizationGrantBuilder fallbackTokenProvider(
            Supplier<CompletableFuture<? extends GrantedOAuth2AccessToken>> fallbackTokenProvider) {
        this.fallbackTokenProvider = requireNonNull(fallbackTokenProvider, "fallbackTokenProvider");
        return this;
    }

    /**
     * Sets an optional hook which gets executed whenever a new token is issued.
     *
     * <p>This can be used in combination with {@link #fallbackTokenProvider(Supplier)} to store a newly issued
     * access token which will then be retrieved by invoking the fallback token provider.
     */
    public OAuth2AuthorizationGrantBuilder newTokenConsumer(
            Consumer<? super GrantedOAuth2AccessToken> newTokenConsumer) {
        this.newTokenConsumer = requireNonNull(newTokenConsumer, "newTokenConsumer");
        return this;
    }

    /**
     * Returns a newly created {@link OAuth2AuthorizationGrant} based on the configuration set so far.
     */
    public OAuth2AuthorizationGrant build() {
        checkState(requestSupplier != null, "accessTokenRequest() is not set.");
        return new DefaultOAuth2AuthorizationGrant(
                accessTokenEndpoint, accessTokenEndpointPath, requestSupplier, responseHandler, refreshBefore,
                fallbackTokenProvider, newTokenConsumer);
    }
}

/*
 * Copyright 2020 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.internal.client.auth.oauth2.ClientCredentialsTokenRequest;
import com.linecorp.armeria.internal.client.auth.oauth2.RefreshAccessTokenRequest;

/**
 * An implementation of OAuth 2.0 Client Credentials Grant flow to obtain Access Token,
 * as per <a href="https://tools.ietf.org/html/rfc6749#section-4.4">[RFC6749], Section 4.4</a>.
 * Implements Access Token loading, storing, obtaining and refreshing.
 */
@UnstableApi
public final class OAuth2ClientCredentialsGrant extends AbstractOAuth2AuthorizationGrant {

    /**
     * Creates a new builder for {@link OAuth2ClientCredentialsGrant}.
     * @param accessTokenEndpoint A {@link WebClient} to facilitate an Access Token request. Must correspond to
     *                            the Access Token endpoint of the OAuth 2 system.
     * @param accessTokenEndpointPath A URI path that corresponds to the Access Token endpoint of the
     */
    public static OAuth2ClientCredentialsGrantBuilder builder(WebClient accessTokenEndpoint,
                                                              String accessTokenEndpointPath) {
        return new OAuth2ClientCredentialsGrantBuilder(accessTokenEndpoint, accessTokenEndpointPath);
    }

    private final ClientCredentialsTokenRequest obtainRequest;

    OAuth2ClientCredentialsGrant(ClientCredentialsTokenRequest obtainRequest,
                                 RefreshAccessTokenRequest refreshRequest, Duration refreshBefore,
                                 @Nullable Supplier<? extends GrantedOAuth2AccessToken> tokenSupplier,
                                 @Nullable Consumer<? super GrantedOAuth2AccessToken> tokenConsumer,
                                 @Nullable Executor executor) {
        super(refreshRequest, refreshBefore, tokenSupplier, tokenConsumer, executor);
        this.obtainRequest = requireNonNull(obtainRequest);
    }

    @Override
    CompletableFuture<GrantedOAuth2AccessToken> obtainAccessToken(
            @Nullable GrantedOAuth2AccessToken token) {
        return obtainRequest.make(token == null ? null : token.scope());
    }
}

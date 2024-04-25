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

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;

/**
 * An implementation of OAuth 2.0 Resource Owner Password Credentials Grant flow to obtain Access Token,
 * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-4.3">[RFC6749], Section 4.3</a>.
 * Implements Access Token loading, storing, obtaining and refreshing.
 *
 * @deprecated Use {@link OAuth2AuthorizationGrantBuilder} with
 *             {@link AccessTokenRequest#ofResourceOwnerPassword(String, String)} instead.
 */
@Deprecated
public final class OAuth2ResourceOwnerPasswordCredentialsGrant implements OAuth2AuthorizationGrant {

    /**
     * Creates a new builder for {@link OAuth2ResourceOwnerPasswordCredentialsGrant}.
     * @param accessTokenEndpoint A {@link WebClient} to facilitate an Access Token request. Must correspond to
     *                            the Access Token endpoint of the OAuth 2 system.
     * @param accessTokenEndpointPath A URI path that corresponds to the Access Token endpoint of the
     *
     * @deprecated Use {@link OAuth2AuthorizationGrant#builder(WebClient, String)} with
     *             {@link AccessTokenRequest#ofResourceOwnerPassword(String, String)} instead.
     */
    @Deprecated
    public static OAuth2ResourceOwnerPasswordCredentialsGrantBuilder builder(WebClient accessTokenEndpoint,
                                                                             String accessTokenEndpointPath) {
        return new OAuth2ResourceOwnerPasswordCredentialsGrantBuilder(accessTokenEndpoint,
                                                                      accessTokenEndpointPath);
    }

    private final OAuth2AuthorizationGrant delegate;

    OAuth2ResourceOwnerPasswordCredentialsGrant(OAuth2AuthorizationGrant delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override
    public CompletionStage<GrantedOAuth2AccessToken> getAccessToken() {
        return delegate.getAccessToken();
    }
}

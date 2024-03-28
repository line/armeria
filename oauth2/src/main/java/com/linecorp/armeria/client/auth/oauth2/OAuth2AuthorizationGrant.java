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
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;

/**
 * Represents an OAuth 2.0 Access Token Grant flow to obtain Access Token.
 */
@UnstableApi
@FunctionalInterface
public interface OAuth2AuthorizationGrant {

    /**
     * Creates a new builder for OAuth 2.0 Access Token Grant flow,
     * as per <a href="https://datatracker.ietf.org/doc/rfc6749/">[RFC6749]</a>.
     *
     * @param accessTokenEndpoint A {@link WebClient} to facilitate an Access Token request. Must correspond to
     *                            the Access Token endpoint of the OAuth 2 system.
     * @param accessTokenEndpointPath A URI path that corresponds to the Access Token endpoint of the
     *                                OAuth 2 system.
     */
    static OAuth2AuthorizationGrantBuilder builder(WebClient accessTokenEndpoint,
                                                   String accessTokenEndpointPath) {
        requireNonNull(accessTokenEndpoint, "accessTokenEndpoint");
        requireNonNull(accessTokenEndpointPath, "accessTokenEndpointPath");
        return new OAuth2AuthorizationGrantBuilder(accessTokenEndpoint, accessTokenEndpointPath);
    }

    /**
     * Produces OAuth 2.0 Access Token
     */
    CompletionStage<GrantedOAuth2AccessToken> getAccessToken();
}

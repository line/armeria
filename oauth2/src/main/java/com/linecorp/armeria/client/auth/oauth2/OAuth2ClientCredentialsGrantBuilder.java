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

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.internal.client.auth.oauth2.AbstractAccessTokenRequest;
import com.linecorp.armeria.internal.client.auth.oauth2.ClientCredentialsTokenRequest;

/**
 * Builds {@link OAuth2ClientCredentialsGrant}.
 */
@UnstableApi
public final class OAuth2ClientCredentialsGrantBuilder
        extends AbstractOAuth2AuthorizationGrantBuilder<OAuth2ClientCredentialsGrantBuilder> {

    /**
     * A common abstraction for the requests implementing various Access Token request/response flows,
     * as per <a href="https://tools.ietf.org/html/rfc6749">[RFC6749]</a>.
     * @param accessTokenEndpoint A {@link WebClient} to facilitate an Access Token request. Must correspond to
     *                            the Access Token endpoint of the OAuth 2 system.
     * @param accessTokenEndpointPath A URI path that corresponds to the Access Token endpoint of the
     */
    OAuth2ClientCredentialsGrantBuilder(WebClient accessTokenEndpoint, String accessTokenEndpointPath) {
        super(accessTokenEndpoint, accessTokenEndpointPath);
    }

    @Override
    protected AbstractAccessTokenRequest buildObtainRequest(WebClient accessTokenEndpoint,
                                                            String accessTokenEndpointPath,
                                                            @Nullable ClientAuthorization clientAuthorization) {
        return new ClientCredentialsTokenRequest(accessTokenEndpoint, accessTokenEndpointPath,
                                                 // clientAuthorization require for this Grant flow
                                                 requireNonNull(clientAuthorization, "clientAuthorization"));
    }

    /**
     * Builds a new instance of {@link OAuth2ClientCredentialsGrant} using configured parameters.
     */
    public OAuth2ClientCredentialsGrant build() {
        return new OAuth2ClientCredentialsGrant((ClientCredentialsTokenRequest) buildObtainRequest(),
                                                buildRefreshRequest(), refreshBefore(),
                                                tokenSupplier(), tokenConsumer(), executor());
    }
}

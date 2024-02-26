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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;

/**
 * Builds {@link OAuth2ClientCredentialsGrant}.
 *
 * @deprecated Use {@link OAuth2AuthorizationGrantBuilder} with
 *             {@link AccessTokenRequest#ofResourceOwnerPassword(String, String)} instead.
 */
@Deprecated
public final class OAuth2ResourceOwnerPasswordCredentialsGrantBuilder
        extends AbstractOAuth2AuthorizationGrantBuilder<OAuth2ResourceOwnerPasswordCredentialsGrantBuilder> {

    @Nullable
    private Supplier<? extends Map.Entry<String, String>> userCredentialsSupplier;

    /**
     * A common abstraction for the requests implementing various Access Token request/response flows,
     * as per <a href="https://datatracker.ietf.org/doc/rfc6749/">[RFC6749]</a>.
     * @param accessTokenEndpoint A {@link WebClient} to facilitate an Access Token request. Must correspond to
     *                            the Access Token endpoint of the OAuth 2 system.
     * @param accessTokenEndpointPath A URI path that corresponds to the Access Token endpoint of the
     */
    OAuth2ResourceOwnerPasswordCredentialsGrantBuilder(
            WebClient accessTokenEndpoint, String accessTokenEndpointPath) {
        super(accessTokenEndpoint, accessTokenEndpointPath);
    }

    /**
     * A supplier of user credentials: "username" and "password" used to grant the Access Token. REQUIRED.
     */
    public OAuth2ResourceOwnerPasswordCredentialsGrantBuilder userCredentials(
            Supplier<? extends Entry<String, String>> userCredentials) {
        userCredentialsSupplier = requireNonNull(userCredentials, "userCredentials");
        return this;
    }

    /**
     * Builds a new instance of {@link OAuth2ResourceOwnerPasswordCredentialsGrant} using configured parameters.
     */
    public OAuth2ResourceOwnerPasswordCredentialsGrant build() {
        checkState(userCredentialsSupplier != null, "userCredentialsSupplier must be set.");
        final ClientAuthentication clientAuthentication = buildClientAuthentication();
        final Supplier<AccessTokenRequest> accessTokenRequestSupplier = () -> {
            final Entry<String, String> userCredentials = userCredentialsSupplier.get();
            requireNonNull(userCredentials, "userCredentials");
            return AccessTokenRequest.ofResourceOwnerPassword(userCredentials.getKey(),
                                                              userCredentials.getValue(),
                                                              clientAuthentication, null);
        };

        final OAuth2AuthorizationGrant delegate = delegate().accessTokenRequest(accessTokenRequestSupplier)
                                                            .build();
        return new OAuth2ResourceOwnerPasswordCredentialsGrant(delegate);
    }
}

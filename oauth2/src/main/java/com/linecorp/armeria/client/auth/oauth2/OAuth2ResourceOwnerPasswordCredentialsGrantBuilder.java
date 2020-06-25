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
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.auth.oauth2.AbstractAccessTokenRequest;
import com.linecorp.armeria.common.auth.oauth2.AccessTokenCapsule;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.ResourceOwnerPasswordCredentialsTokenRequest;

/**
 * Builds {@link OAuth2ClientCredentialsGrant}.
 */
public class OAuth2ResourceOwnerPasswordCredentialsGrantBuilder extends
                                                                AbstractOAuth2AuthorizationGrantBuilder {

    @Nullable
    private Supplier<Map.Entry<String, String>> userCredentialsSupplier;

    /**
     * A common abstraction for the requests implementing various Access Token request/response flows,
     * as per <a href="https://tools.ietf.org/html/rfc6749">[RFC6749]</a>.
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
    public OAuth2ResourceOwnerPasswordCredentialsGrantBuilder userCredentialsSupplier(
            Supplier<Entry<String, String>> userCredentialsSupplier) {
        this.userCredentialsSupplier = requireNonNull(userCredentialsSupplier, "userCredentialsSupplier");
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
     * authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     */
    @Override
    protected OAuth2ResourceOwnerPasswordCredentialsGrantBuilder clientAuthorization(
            Supplier<String> authorizationSupplier, String authorizationType) {
        super.clientAuthorization(authorizationSupplier, authorizationType);
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
     * {@code Basic} authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     */
    @Override
    protected OAuth2ResourceOwnerPasswordCredentialsGrantBuilder clientBasicAuthorization(
            Supplier<String> authorizationSupplier) {
        super.clientBasicAuthorization(authorizationSupplier);
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on client credentials and
     * authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     */
    @Override
    protected OAuth2ResourceOwnerPasswordCredentialsGrantBuilder clientCredentials(
            Supplier<Map.Entry<String, String>> credentialsSupplier, String authorizationType) {
        super.clientCredentials(credentialsSupplier, authorizationType);
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on client credentials and
     * {@code Basic} authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     */
    @Override
    protected OAuth2ResourceOwnerPasswordCredentialsGrantBuilder clientCredentials(
            Supplier<Map.Entry<String, String>> credentialsSupplier) {
        super.clientCredentials(credentialsSupplier);
        return this;
    }

    /**
     * A period when the token should be refreshed proactively prior to its expiry.
     */
    @Override
    protected OAuth2ResourceOwnerPasswordCredentialsGrantBuilder refreshBefore(Duration refreshBefore) {
        super.refreshBefore(refreshBefore);
        return this;
    }

    /**
     * A {@link Supplier} to load Access Token from, to be able to restore the previous session. OPTIONAL.
     */
    @Override
    protected OAuth2ResourceOwnerPasswordCredentialsGrantBuilder tokenSupplier(
            Supplier<AccessTokenCapsule> tokenSupplier) {
        super.tokenSupplier(tokenSupplier);
        return this;
    }

    /**
     * A {@link Consumer} to store Access Token to, to be able restore the previous session. OPTIONAL.
     */
    @Override
    protected OAuth2ResourceOwnerPasswordCredentialsGrantBuilder tokenConsumer(
            Consumer<AccessTokenCapsule> tokenConsumer) {
        super.tokenConsumer(tokenConsumer);
        return this;
    }

    @Override
    protected AbstractAccessTokenRequest buildObtainRequest(WebClient accessTokenEndpoint,
                                                            String accessTokenEndpointPath,
                                                            @Nullable ClientAuthorization clientAuthorization) {
        return new ResourceOwnerPasswordCredentialsTokenRequest(
                accessTokenEndpoint, accessTokenEndpointPath, clientAuthorization,
                requireNonNull(userCredentialsSupplier, "userCredentialsSupplier"));
    }

    /**
     * Builds a new instance of {@link OAuth2ResourceOwnerPasswordCredentialsGrant} using configured parameters.
     */
    public OAuth2ResourceOwnerPasswordCredentialsGrant build() {
        return new OAuth2ResourceOwnerPasswordCredentialsGrant(
                (ResourceOwnerPasswordCredentialsTokenRequest) buildObtainRequest(),
                buildRefreshRequest(), refreshBefore(),
                tokenSupplier(), tokenConsumer());
    }
}

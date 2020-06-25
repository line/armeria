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
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.auth.oauth2.AbstractAccessTokenRequest;
import com.linecorp.armeria.common.auth.oauth2.AccessTokenCapsule;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.RefreshAccessTokenRequest;

abstract class AbstractOAuth2AuthorizationGrantBuilder {

    private final WebClient accessTokenEndpoint;
    private final String accessTokenEndpointPath;

    @Nullable
    private ClientAuthorization clientAuthorization;

    @Nullable
    private Duration refreshBefore;

    @Nullable
    private Supplier<AccessTokenCapsule> tokenSupplier;

    @Nullable
    private Consumer<AccessTokenCapsule> tokenConsumer;

    /**
     * A common abstraction for the requests implementing various Access Token request/response flows,
     * as per <a href="https://tools.ietf.org/html/rfc6749">[RFC6749]</a>.
     *
     * @param accessTokenEndpoint A {@link WebClient} to facilitate an Access Token request. Must correspond to
     *                            the Access Token endpoint of the OAuth 2 system.
     * @param accessTokenEndpointPath A URI path that corresponds to the Access Token endpoint of the
     *                                OAuth 2 system.
     */
    AbstractOAuth2AuthorizationGrantBuilder(WebClient accessTokenEndpoint, String accessTokenEndpointPath) {
        this.accessTokenEndpoint = requireNonNull(accessTokenEndpoint, "accessTokenEndpoint");
        this.accessTokenEndpointPath = requireNonNull(accessTokenEndpointPath, "accessTokenEndpointPath");
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
    protected AbstractOAuth2AuthorizationGrantBuilder clientAuthorization(
            Supplier<String> authorizationSupplier, String authorizationType) {
        clientAuthorization = ClientAuthorization.ofAuthorization(authorizationSupplier, authorizationType);
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
     * {@code Basic} authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     */
    protected AbstractOAuth2AuthorizationGrantBuilder clientBasicAuthorization(
            Supplier<String> authorizationSupplier) {
        clientAuthorization = ClientAuthorization.ofBasicAuthorization(authorizationSupplier);
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
    protected AbstractOAuth2AuthorizationGrantBuilder clientCredentials(
            Supplier<Map.Entry<String, String>> credentialsSupplier, String authorizationType) {
        clientAuthorization = ClientAuthorization.ofCredentials(credentialsSupplier, authorizationType);
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on client credentials and
     * {@code Basic} authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     */
    protected AbstractOAuth2AuthorizationGrantBuilder clientCredentials(
            Supplier<Map.Entry<String, String>> credentialsSupplier) {
        clientAuthorization = ClientAuthorization.ofCredentials(credentialsSupplier);
        return this;
    }

    /**
     * A period when the token should be refreshed proactively prior to its expiry.
     */
    protected AbstractOAuth2AuthorizationGrantBuilder refreshBefore(Duration refreshBefore) {
        this.refreshBefore = requireNonNull(refreshBefore, "refreshBefore");
        return this;
    }

    @Nullable
    protected Duration refreshBefore() {
        return refreshBefore;
    }

    @Nullable
    protected Supplier<AccessTokenCapsule> tokenSupplier() {
        return tokenSupplier;
    }

    /**
     * A {@link Supplier} to load Access Token from, to be able to restore the previous session. OPTIONAL.
     */
    protected AbstractOAuth2AuthorizationGrantBuilder tokenSupplier(
            Supplier<AccessTokenCapsule> tokenSupplier) {
        this.tokenSupplier = requireNonNull(tokenSupplier, "tokenSupplier");
        return this;
    }

    @Nullable
    protected Consumer<AccessTokenCapsule> tokenConsumer() {
        return tokenConsumer;
    }

    /**
     * A {@link Consumer} to store Access Token to, to be able restore the previous session. OPTIONAL.
     */
    protected AbstractOAuth2AuthorizationGrantBuilder tokenConsumer(
            Consumer<AccessTokenCapsule> tokenConsumer) {
        this.tokenConsumer = requireNonNull(tokenConsumer, "tokenConsumer");
        return this;
    }

    protected abstract AbstractAccessTokenRequest buildObtainRequest(
            WebClient accessTokenEndpoint, String accessTokenEndpointPath,
            @Nullable ClientAuthorization clientAuthorization);

    protected AbstractAccessTokenRequest buildObtainRequest() {
        return buildObtainRequest(accessTokenEndpoint, accessTokenEndpointPath, clientAuthorization);
    }

    protected RefreshAccessTokenRequest buildRefreshRequest() {
        return new RefreshAccessTokenRequest(accessTokenEndpoint, accessTokenEndpointPath, clientAuthorization);
    }
}

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
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.internal.client.auth.oauth2.AbstractAccessTokenRequest;
import com.linecorp.armeria.internal.client.auth.oauth2.RefreshAccessTokenRequest;

@SuppressWarnings("rawtypes")
abstract class AbstractOAuth2AuthorizationGrantBuilder<T extends AbstractOAuth2AuthorizationGrantBuilder> {

    /**
     * A period when the token should be refreshed proactively prior to its expiry.
     */
    private static final Duration DEFAULT_REFRESH_BEFORE = Duration.ofMinutes(1L); // 1 minute

    private final WebClient accessTokenEndpoint;
    private final String accessTokenEndpointPath;

    @Nullable
    private ClientAuthorization clientAuthorization;

    private Duration refreshBefore = DEFAULT_REFRESH_BEFORE;

    @Nullable
    private Supplier<? extends GrantedOAuth2AccessToken> tokenSupplier;

    @Nullable
    private Consumer<? super GrantedOAuth2AccessToken> tokenConsumer;

    @Nullable
    private Executor executor;

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
    @SuppressWarnings("unchecked")
    public final T clientAuthorization(
            Supplier<String> authorizationSupplier, String authorizationType) {
        clientAuthorization = ClientAuthorization.ofAuthorization(authorizationSupplier, authorizationType);
        return (T) this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
     * {@code Basic} authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     */
    @SuppressWarnings("unchecked")
    public final T clientBasicAuthorization(Supplier<String> authorizationSupplier) {
        clientAuthorization = ClientAuthorization.ofBasicAuthorization(authorizationSupplier);
        return (T) this;
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
    @SuppressWarnings("unchecked")
    public final T clientCredentials(
            Supplier<? extends Map.Entry<String, String>> credentialsSupplier, String authorizationType) {
        clientAuthorization = ClientAuthorization.ofCredentials(credentialsSupplier, authorizationType);
        return (T) this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on client credentials and
     * {@code Basic} authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     */
    @SuppressWarnings("unchecked")
    public final T clientCredentials(
            Supplier<? extends Map.Entry<String, String>> credentialsSupplier) {
        clientAuthorization = ClientAuthorization.ofCredentials(credentialsSupplier);
        return (T) this;
    }

    /**
     * A period when the token should be refreshed proactively prior to its expiry.
     */
    @SuppressWarnings("unchecked")
    public final T refreshBefore(Duration refreshBefore) {
        this.refreshBefore = requireNonNull(refreshBefore, "refreshBefore");
        return (T) this;
    }

    final Duration refreshBefore() {
        return refreshBefore;
    }

    /**
     * A {@link Supplier} to load Access Token from, to be able to restore the previous session. OPTIONAL.
     */
    @SuppressWarnings("unchecked")
    public final T tokenSupplier(Supplier<? extends GrantedOAuth2AccessToken> tokenSupplier) {
        this.tokenSupplier = requireNonNull(tokenSupplier, "tokenSupplier");
        return (T) this;
    }

    @Nullable
    final Supplier<? extends GrantedOAuth2AccessToken> tokenSupplier() {
        return tokenSupplier;
    }

    /**
     * A {@link Consumer} to store Access Token to, to be able restore the previous session. OPTIONAL.
     */
    @SuppressWarnings("unchecked")
    public final T tokenConsumer(Consumer<? super GrantedOAuth2AccessToken> tokenConsumer) {
        this.tokenConsumer = requireNonNull(tokenConsumer, "tokenConsumer");
        return (T) this;
    }

    @Nullable
    final Consumer<? super GrantedOAuth2AccessToken> tokenConsumer() {
        return tokenConsumer;
    }

    /**
     * An optional {@link Executor} that facilitates asynchronous access token obtain and refresh operations.
     */
    @SuppressWarnings("unchecked")
    public final T executor(Executor executor) {
        this.executor = requireNonNull(executor, "executor");
        return (T) this;
    }

    @Nullable
    final Executor executor() {
        return executor;
    }

    abstract AbstractAccessTokenRequest buildObtainRequest(
            WebClient accessTokenEndpoint, String accessTokenEndpointPath,
            @Nullable ClientAuthorization clientAuthorization);

    final AbstractAccessTokenRequest buildObtainRequest() {
        return buildObtainRequest(accessTokenEndpoint, accessTokenEndpointPath, clientAuthorization);
    }

    final RefreshAccessTokenRequest buildRefreshRequest() {
        return new RefreshAccessTokenRequest(accessTokenEndpoint, accessTokenEndpointPath, clientAuthorization);
    }
}

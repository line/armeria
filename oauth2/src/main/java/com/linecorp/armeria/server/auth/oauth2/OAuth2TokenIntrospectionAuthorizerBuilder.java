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

package com.linecorp.armeria.server.auth.oauth2;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.OAuth2TokenDescriptor;
import com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Endpoint;
import com.linecorp.armeria.server.auth.Authorizer;

/**
 * Builds a {@link OAuth2TokenIntrospectionAuthorizer} instance.
 */
@UnstableApi
public final class OAuth2TokenIntrospectionAuthorizerBuilder {

    public static final String DEFAULT_CACHE_SPEC = "maximumSize=1024,expireAfterWrite=1h";
    private static final CaffeineSpec DEFAULT_CACHE_SPEC_OBJ = CaffeineSpec.parse(DEFAULT_CACHE_SPEC);

    private final WebClient introspectionEndpoint;
    private final String introspectionEndpointPath;

    @Nullable
    private Supplier<ClientAuthentication> clientAuthenticationSupplier;

    @Nullable
    private String accessTokenType;

    @Nullable
    private String realm;

    private final ImmutableSet.Builder<String> permittedScope = ImmutableSet.builder();

    @Nullable
    private CaffeineSpec cacheSpec;

    /**
     * Constructs new builder for OAuth 2.0 Token Introspection {@link Authorizer},
     * as per<a href="https://datatracker.ietf.org/doc/html/rfc7662#section-2">[RFC7662], Section 2</a>.
     *
     * @param introspectionEndpoint A {@link WebClient} to facilitate the Token Introspection request. Must
     *                              correspond to the Token Introspection endpoint of the OAuth 2.0 system.
     * @param introspectionEndpointPath A URI path that corresponds to the Token Introspection endpoint of the
     *                                  OAuth 2.0 system.
     */
    OAuth2TokenIntrospectionAuthorizerBuilder(WebClient introspectionEndpoint,
                                              String introspectionEndpointPath) {
        this.introspectionEndpoint = requireNonNull(introspectionEndpoint, "introspectionEndpoint");
        this.introspectionEndpointPath =
                requireNonNull(introspectionEndpointPath, "introspectionEndpointPath");
    }

    /**
     * Provides client authorization for the OAuth 2.0 Introspection requests based on encoded authorization
     * token and authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     *
     * @deprecated Use {@link #clientAuthentication(Supplier)} with
     *             {@link ClientAuthentication#ofAuthorization(String, String)} instead.
     */
    @Deprecated
    public OAuth2TokenIntrospectionAuthorizerBuilder clientAuthorization(
            Supplier<String> authorizationSupplier, String authorizationType) {
        final ClientAuthorization clientAuthorization =
                ClientAuthorization.ofAuthorization(authorizationSupplier, authorizationType);
        return clientAuthentication(clientAuthorization.toClientAuthentication());
    }

    /**
     * Provides client authorization for the OAuth 2.0 Introspection requests based on encoded authorization
     * token and {@code Basic} authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     *
     * @deprecated Use {@link #clientAuthentication(Supplier)} with {@link ClientAuthentication#ofBasic(String)}
     *             instead.
     */
    @Deprecated
    public OAuth2TokenIntrospectionAuthorizerBuilder clientBasicAuthorization(
            Supplier<String> authorizationSupplier) {
        requireNonNull(authorizationSupplier, "authorizationSupplier");
        final ClientAuthorization clientAuthorization =
                ClientAuthorization.ofBasicAuthorization(authorizationSupplier);
        return clientAuthentication(clientAuthorization.toClientAuthentication());
    }

    /**
     * Provides client authorization for the OAuth 2.0 Introspection requests based on client credentials and
     * authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     *
     * @deprecated Use {@link #clientAuthentication(Supplier)} with
     *             {@link ClientAuthentication#ofClientPassword(String, String)} instead.
     */
    @Deprecated
    public OAuth2TokenIntrospectionAuthorizerBuilder clientCredentials(
            Supplier<? extends Map.Entry<String, String>> credentialsSupplier, String authorizationType) {
        final ClientAuthorization clientAuthorization = ClientAuthorization.ofCredentials(credentialsSupplier,
                                                                                          authorizationType);
        return clientAuthentication(clientAuthorization.toClientAuthentication());
    }

    /**
     * Provides client authorization for the OAuth 2.0 Introspection requests based on client credentials and
     * {@code Basic} authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     *
     * @deprecated Use {@link #clientAuthentication(Supplier)} with
     *             {@link ClientAuthentication#ofClientPassword(String, String)} instead.
     */
    @Deprecated
    public OAuth2TokenIntrospectionAuthorizerBuilder clientCredentials(
            Supplier<? extends Map.Entry<String, String>> credentialsSupplier) {
        final ClientAuthorization clientAuthorization = ClientAuthorization.ofCredentials(credentialsSupplier);
        return clientAuthentication(clientAuthorization.toClientAuthentication());
    }

    /**
     * Provides client authentication for the OAuth 2.0 Introspection requests, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder clientAuthentication(
            ClientAuthentication clientAuthentication) {
        requireNonNull(clientAuthentication, "clientAuthentication");
        clientAuthentication(() -> clientAuthentication);
        return this;
    }

    /**
     * Provides client authentication for the OAuth 2.0 Introspection requests, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder clientAuthentication(
            Supplier<ClientAuthentication> clientAuthenticationSupplier) {
        requireNonNull(clientAuthenticationSupplier, "clientAuthenticationSupplier");
        this.clientAuthenticationSupplier = clientAuthenticationSupplier;
        return this;
    }

    /**
     * Access Token type permitted by this authorizer,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-7.1">[RFC6749], Section 7.1</a>.
     * The authorizer will accept any type if empty. OPTIONAL.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder accessTokenType(String accessTokenType) {
        this.accessTokenType = requireNonNull(accessTokenType, "accessTokenType");
        return this;
    }

    /**
     * An HTTP Realm - a name designating of the protected area. OPTIONAL.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder realm(String realm) {
        this.realm = requireNonNull(realm, "realm");
        return this;
    }

    /**
     * An {@link Iterable} of case-sensitive scope strings permitted by this authorizer.
     * The authorizer will accept any scope if empty.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder permittedScope(Iterable<String> scope) {
        permittedScope.addAll(requireNonNull(scope, "scope"));
        return this;
    }

    /**
     * An array of case-sensitive scope strings permitted by this authorizer.
     * The authorizer will accept any scope if empty.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder permittedScope(String... scope) {
        permittedScope.add(requireNonNull(scope, "scope"));
        return this;
    }

    /**
     * Provides caching facility for OAuth 2.0 {@link OAuth2TokenDescriptor} in order to avoid continuous Token
     * Introspection as per <a href="https://datatracker.ietf.org/doc/html/rfc7662#section-2.2">[RFC7662], Section 2.2</a>.
     * Sets the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the tokens.
     * If not set, {@value DEFAULT_CACHE_SPEC} is used by default.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder cacheSpec(String cacheSpec) {
        this.cacheSpec = CaffeineSpec.parse(cacheSpec); // parse right away
        return this;
    }

    /**
     * Builds a new instance of {@link OAuth2TokenIntrospectionAuthorizer} using configured parameters.
     */
    public OAuth2TokenIntrospectionAuthorizer build() {
        // init introspection request
        final OAuth2Endpoint<OAuth2TokenDescriptor> oAuth2Endpoint =
                new OAuth2Endpoint<>(introspectionEndpoint, introspectionEndpointPath,
                                     OAuth2TokenIntrospectionResponseHandler.INSTANCE);
        final TokenIntrospection tokenIntrospection =
                new TokenIntrospection(oAuth2Endpoint, clientAuthenticationSupplier);

        final Cache<String, OAuth2TokenDescriptor> tokenCache =
                Caffeine.from(cacheSpec == null ? DEFAULT_CACHE_SPEC_OBJ : cacheSpec).build();

        return new OAuth2TokenIntrospectionAuthorizer(tokenCache,
                                                      accessTokenType, realm, permittedScope.build(),
                                                      tokenIntrospection);
    }
}

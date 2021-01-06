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

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.OAuth2TokenDescriptor;
import com.linecorp.armeria.internal.server.auth.oauth2.TokenIntrospectionRequest;
import com.linecorp.armeria.server.auth.Authorizer;

/**
 * Builds a {@link OAuth2TokenIntrospectionAuthorizer} instance.
 */
@UnstableApi
public final class OAuth2TokenIntrospectionAuthorizerBuilder {

    private static final long DEFAULT_CACHE_MAX_SIZE = 1000;
    private static final Duration DEFAULT_CACHE_MAX_AGE = Duration.ofHours(1L);

    private static Cache<String, OAuth2TokenDescriptor> createDefaultCache() {
        final CacheBuilder<Object, Object> cacheBuilder =
                CacheBuilder.newBuilder().maximumSize(DEFAULT_CACHE_MAX_SIZE)
                            .concurrencyLevel(Runtime.getRuntime().availableProcessors());
        cacheBuilder.expireAfterWrite(DEFAULT_CACHE_MAX_AGE);
        return cacheBuilder.build();
    }

    private final WebClient introspectionEndpoint;
    private final String introspectionEndpointPath;

    @Nullable
    private ClientAuthorization clientAuthorization;

    @Nullable
    private String accessTokenType;

    @Nullable
    private String realm;

    private final ImmutableSet.Builder<String> permittedScope = ImmutableSet.builder();

    @Nullable
    private Cache<String, OAuth2TokenDescriptor> tokenCache;

    /**
     * Constructs new new builder for OAuth 2.0 Token Introspection {@link Authorizer},
     * as per<a href="https://tools.ietf.org/html/rfc7662#section-2">[RFC7662], Section 2</a>.
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
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder clientAuthorization(
            Supplier<String> authorizationSupplier, String authorizationType) {
        clientAuthorization = ClientAuthorization.ofAuthorization(authorizationSupplier, authorizationType);
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 Introspection requests based on encoded authorization
     * token and {@code Basic} authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder clientBasicAuthorization(
            Supplier<String> authorizationSupplier) {
        clientAuthorization = ClientAuthorization.ofBasicAuthorization(authorizationSupplier);
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 Introspection requests based on client credentials and
     * authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder clientCredentials(
            Supplier<? extends Map.Entry<String, String>> credentialsSupplier, String authorizationType) {
        clientAuthorization = ClientAuthorization.ofCredentials(credentialsSupplier, authorizationType);
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 Introspection requests based on client credentials and
     * {@code Basic} authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder clientCredentials(
            Supplier<? extends Map.Entry<String, String>> credentialsSupplier) {
        clientAuthorization = ClientAuthorization.ofCredentials(credentialsSupplier);
        return this;
    }

    /**
     * Access Token type permitted by this authorizer,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-7.1">[RFC6749], Section 7.1</a>.
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
     * An array of of case-sensitive scope strings permitted by this authorizer.
     * The authorizer will accept any scope if empty.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder permittedScope(String... scope) {
        permittedScope.add(requireNonNull(scope, "scope"));
        return this;
    }

    /**
     * Provides caching facility for OAuth 2.0 {@link OAuth2TokenDescriptor} in order to avoid continuous Token
     * Introspection as per <a href="https://tools.ietf.org/html/rfc7662#section-2.2">[RFC7662], Section 2.2</a>.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder tokenCache(
            Cache<String, OAuth2TokenDescriptor> tokenCache) {
        this.tokenCache = requireNonNull(tokenCache, "tokenCache");
        return this;
    }

    /**
     * Provides caching facility for OAuth 2.0 {@link OAuth2TokenDescriptor} in order to avoid continuous Token
     * Introspection as per <a href="https://tools.ietf.org/html/rfc7662#section-2.2">[RFC7662], Section 2.2</a>.
     * @param maxSize Specifies the maximum number of entries the cache may contain.
     * @param maxAge Specifies that each entry should be automatically removed from the cache once given
     *               {@link Duration} has elapsed after the entry's creation, or after the most recent
     *               replacement of its value.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder tokenCacheWithAgePolicy(
            long maxSize, Duration maxAge) {
        final CacheBuilder<Object, Object> cacheBuilder =
                CacheBuilder.newBuilder().maximumSize(maxSize)
                            .concurrencyLevel(Runtime.getRuntime().availableProcessors());
        cacheBuilder.expireAfterWrite(requireNonNull(maxAge, "maxAge"));
        tokenCache = cacheBuilder.build();
        return this;
    }

    /**
     * Provides caching facility for OAuth 2.0 {@link OAuth2TokenDescriptor} in order to avoid continuous Token
     * Introspection as per <a href="https://tools.ietf.org/html/rfc7662#section-2.2">[RFC7662], Section 2.2</a>.
     * @param maxSize Specifies the maximum number of entries the cache may contain.
     * @param maxIdle Specifies that each entry should be automatically removed from the cache once given
     *                {@link Duration} has elapsed after the entry's creation, the most recent replacement
     *                of its value, or its last access.
     */
    public OAuth2TokenIntrospectionAuthorizerBuilder tokenCacheWithAccessPolicy(
            long maxSize, Duration maxIdle) {
        final CacheBuilder<Object, Object> cacheBuilder =
                CacheBuilder.newBuilder().maximumSize(maxSize)
                            .concurrencyLevel(Runtime.getRuntime().availableProcessors());
        cacheBuilder.expireAfterAccess(requireNonNull(maxIdle, "maxIdle"));
        tokenCache = cacheBuilder.build();
        return this;
    }

    /**
     * Builds a new instance of {@link OAuth2TokenIntrospectionAuthorizer} using configured parameters.
     */
    public OAuth2TokenIntrospectionAuthorizer build() {
        // init introspection request
        final TokenIntrospectionRequest introspectionRequest =
                new TokenIntrospectionRequest(introspectionEndpoint,
                                              requireNonNull(introspectionEndpointPath,
                                                             "introspectionEndpointPath"),
                                              clientAuthorization);

        return new OAuth2TokenIntrospectionAuthorizer(tokenCache == null ? createDefaultCache() : tokenCache,
                                                      accessTokenType, realm, permittedScope.build(),
                                                      introspectionRequest);
    }
}

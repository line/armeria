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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.UNSUPPORTED_TOKEN_TYPE;
import static com.linecorp.armeria.server.auth.oauth2.OAuth2TokenScopeValidator.INSUFFICIENT_SCOPE;
import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.concurrent.CompletionStage;

import com.github.benmanes.caffeine.cache.Cache;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.common.auth.oauth2.OAuth2TokenDescriptor;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AbstractAuthorizerWithHandlers;
import com.linecorp.armeria.server.auth.AuthFailureHandler;
import com.linecorp.armeria.server.auth.AuthorizationStatus;

import io.netty.util.AttributeKey;

/**
 * Determines whether a given {@link OAuth2Token} is authorized for the service registered in using OAuth 2.0
 * Token Introspection. {@code ctx} can be used for storing authorization information about the request for use
 * in business logic.
 */
@UnstableApi
public final class OAuth2TokenIntrospectionAuthorizer extends AbstractAuthorizerWithHandlers<OAuth2Token> {

    /**
     * Returns a newly created {@link OAuth2TokenIntrospectionAuthorizerBuilder}.
     *
     * @param introspectionEndpoint A {@link WebClient} to facilitate the Token Introspection request. Must
     *                              correspond to the Token Introspection endpoint of the OAuth 2.0 system.
     * @param introspectionEndpointPath A URI path that corresponds to the Token Introspection endpoint of the
     *                                  OAuth 2.0 system.
     */
    public static OAuth2TokenIntrospectionAuthorizerBuilder builder(WebClient introspectionEndpoint,
                                                                    String introspectionEndpointPath) {
        return new OAuth2TokenIntrospectionAuthorizerBuilder(introspectionEndpoint, introspectionEndpointPath);
    }

    static final AttributeKey<Integer> ERROR_CODE = AttributeKey.valueOf("x-oauth2-error");
    static final AttributeKey<String> ERROR_TYPE = AttributeKey.valueOf("x-oauth2-error-type");
    static final String INVALID_TOKEN = "invalid_token";

    private static final CompletionStage<AuthorizationStatus> SUCCESS_STATUS_FUTURE =
            UnmodifiableFuture.completedFuture(AuthorizationStatus.ofSuccess());

    private final Cache<String, OAuth2TokenDescriptor> tokenCache;
    private final Set<String> permittedScope;
    @Nullable
    private final String accessTokenType;
    @Nullable
    private final String realm;
    private final TokenIntrospection tokenIntrospection;
    private final AuthFailureHandler authFailureHandler;
    private final AuthorizationStatus failureStatus;
    private final CompletionStage<AuthorizationStatus> failureStatusFuture;

    OAuth2TokenIntrospectionAuthorizer(Cache<String, OAuth2TokenDescriptor> tokenCache,
                                       @Nullable String accessTokenType, @Nullable String realm,
                                       Set<String> permittedScope,
                                       TokenIntrospection tokenIntrospection) {
        this.tokenCache = requireNonNull(tokenCache, "tokenCache");
        this.accessTokenType = accessTokenType;
        this.realm = realm;
        this.permittedScope = requireNonNull(permittedScope, "permittedScope");
        this.tokenIntrospection =
                requireNonNull(tokenIntrospection, "tokenIntrospection");
        authFailureHandler =
                new OAuth2AuthorizationFailureHandler(accessTokenType, realm,
                                                      permittedScope.isEmpty() ? null
                                                                               :
                                                      String.join(" ", permittedScope));
        failureStatus = AuthorizationStatus.ofFailure(authFailureHandler);
        failureStatusFuture = UnmodifiableFuture.completedFuture(failureStatus);
    }

    /**
     * Scopes permitted by this authorizer. The authorizer will accept any scope if empty.
     */
    public Set<String> permittedScope() {
        return permittedScope;
    }

    /**
     * Authorization type permitted by this authorizer. The authorizer will accept any type if empty.
     * One of the registered HTTP authentication schemes as per
     * <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     * HTTP Authentication Scheme Registry</a>.
     */
    @Nullable
    public String accessTokenType() {
        return accessTokenType;
    }

    /**
     * An HTTP Realm - a name designating the protected area. OPTIONAL.
     */
    @Nullable
    public String realm() {
        return realm;
    }

    /**
     * An instance of {@link OAuth2AuthorizationFailureHandler}.
     */
    public AuthFailureHandler failureHandler() {
        return authFailureHandler;
    }

    @Override
    public CompletionStage<AuthorizationStatus> authorizeAndSupplyHandlers(ServiceRequestContext ctx,
                                                                           @Nullable OAuth2Token data) {

        if (data == null) {
            // no access token present
            return failureStatusFuture;
        }
        final String accessToken = data.accessToken();
        final OAuth2TokenDescriptor tokenDescriptor = tokenCache.getIfPresent(accessToken);
        if (tokenDescriptor != null) {
            // just re-validate existing token
            return validateDescriptor(ctx, tokenDescriptor) ? SUCCESS_STATUS_FUTURE : failureStatusFuture;
        }
        // using OAuth 2.0 introspection request to obtain the token descriptor
        return tokenIntrospection.introspect(accessToken).thenApply(descriptor -> {
            // first, authorize the new token descriptor
            if (!authorizeNewDescriptor(ctx, descriptor)) {
                return failureStatus;
            }
            // cache the new token descriptor
            tokenCache.put(accessToken, descriptor);
            // validate new token
            return validateDescriptor(ctx, descriptor) ? AuthorizationStatus.ofSuccess()
                                                       : failureStatus;
        });
    }

    private boolean validateDescriptor(ServiceRequestContext ctx, OAuth2TokenDescriptor tokenDescriptor) {
        // check whether the token still valid
        if (!tokenDescriptor.isValid()) {
            ctx.setAttr(ERROR_CODE, HttpStatus.UNAUTHORIZED.code());
            ctx.setAttr(ERROR_TYPE, INVALID_TOKEN);
            return false;
        }

        // set OAuth 2 token to the request context for optional application-level validation
        OAuth2TokenScopeValidator.setOauth2Context(ctx, tokenDescriptor, realm);
        return true;
    }

    private boolean authorizeNewDescriptor(ServiceRequestContext ctx, OAuth2TokenDescriptor tokenDescriptor) {
        // check whether the token active
        if (!tokenDescriptor.isActive()) {
            ctx.setAttr(ERROR_CODE, HttpStatus.UNAUTHORIZED.code());
            ctx.setAttr(ERROR_TYPE, INVALID_TOKEN);
            return false;
        }

        // check whether the token type permitted against configured authorization type
        if (accessTokenType != null && !accessTokenType.equalsIgnoreCase(tokenDescriptor.tokenType())) {
            ctx.setAttr(ERROR_CODE, HttpStatus.BAD_REQUEST.code());
            ctx.setAttr(ERROR_TYPE, UNSUPPORTED_TOKEN_TYPE);
            return false;
        }

        // check whether the token already valid
        if (!tokenDescriptor.isNotBefore()) {
            ctx.setAttr(ERROR_CODE, HttpStatus.UNAUTHORIZED.code());
            ctx.setAttr(ERROR_TYPE, INVALID_TOKEN);
            return false;
        }

        // check the scopes for access permission
        final boolean result = OAuth2TokenScopeValidator.validateScope(tokenDescriptor, permittedScope);
        if (!result) {
            ctx.setAttr(ERROR_CODE, HttpStatus.FORBIDDEN.code());
            ctx.setAttr(ERROR_TYPE, INSUFFICIENT_SCOPE);
        }
        return result;
    }
}

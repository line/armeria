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

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.common.auth.oauth2.TokenDescriptor;
import com.linecorp.armeria.common.auth.oauth2.TokenIntrospectionRequest;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthFailureHandler;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.server.auth.Authorizer;

import io.netty.util.AttributeKey;

/**
 * Determines whether a given {@link OAuth2Token} is authorized for the service registered in using OAuth 2.0
 * Token Introspection. {@code ctx} can be used for storing authorization information about the request for use
 * in business logic.
 */
public class OAuth2TokenIntrospectionAuthorizer implements Authorizer<OAuth2Token> {

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
    static final String UNSUPPORTED_TOKEN_TYPE = "unsupported_token_type";
    static final String INVALID_TOKEN = "invalid_token";
    static final String INSUFFICIENT_SCOPE = "insufficient_scope";

    private final Cache<String, TokenDescriptor> tokenCache;
    private final Set<String> permittedScope;
    @Nullable
    private final String accessTokenType;
    @Nullable
    private final String realm;
    private final TokenIntrospectionRequest tokenIntrospectionRequest;
    private final AuthFailureHandler authFailureHandler;

    OAuth2TokenIntrospectionAuthorizer(Cache<String, TokenDescriptor> tokenCache,
                                       @Nullable String accessTokenType, @Nullable String realm,
                                       Set<String> permittedScope,
                                       TokenIntrospectionRequest tokenIntrospectionRequest) {
        this.tokenCache = requireNonNull(tokenCache, "tokenCache");
        this.accessTokenType = accessTokenType;
        this.realm = realm;
        this.permittedScope = requireNonNull(permittedScope, "permittedScope");
        this.tokenIntrospectionRequest =
                requireNonNull(tokenIntrospectionRequest, "tokenIntrospectionRequest");
        authFailureHandler =
                new OAuth2AuthorizationFailureHandler(accessTokenType, realm, String.join(" ", permittedScope));
    }

    /**
     * Creates a new {@link AuthService} that authorizes HTTP requests using OAuth 2.0 Token Introspection.
     */
    public AuthService asAuthService(HttpService delegate) {
        return AuthService.builder()
                          .addOAuth2(this)
                          .onFailure(authFailureHandler())
                          .build(delegate);
    }

    /**
     * Creates a new {@link AuthService} that authorizes HTTP requests using OAuth 2.0 Token Introspection.
     * Returns this service as a decorator.
     */
    public Function<? super HttpService, ? extends HttpService> asDecorator() {
        return this::asAuthService;
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
    public AuthFailureHandler authFailureHandler() {
        return authFailureHandler;
    }

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, OAuth2Token data) {

        final String accessToken = data.accessToken();
        final TokenDescriptor tokenDescriptor = tokenCache.getIfPresent(accessToken);
        if (tokenDescriptor != null) {
            // just re-validate existing token
            return CompletableFuture.completedFuture(validateDescriptor(ctx, tokenDescriptor));
        }
        // using OAuth2 introspection request to obtain the token introspection capsule
        return tokenIntrospectionRequest.make(accessToken).thenApply(descriptor -> {
            // first, authorize the new token descriptor
            if (!authorizeNewDescriptor(ctx, descriptor)) {
                return false;
            }
            // cache the new token descriptor
            tokenCache.put(accessToken, descriptor);
            // validate new token
            return validateDescriptor(ctx, descriptor);
        });
    }

    @SuppressWarnings("MethodMayBeStatic")
    private boolean validateDescriptor(ServiceRequestContext ctx, TokenDescriptor tokenDescriptor) {
        // check whether the token still valid
        if (!tokenDescriptor.isValid()) {
            ctx.setAttr(ERROR_CODE, HttpStatus.UNAUTHORIZED.code());
            ctx.setAttr(ERROR_TYPE, INVALID_TOKEN);
            return false;
        }

        return true;
    }

    private boolean authorizeNewDescriptor(ServiceRequestContext ctx, TokenDescriptor tokenDescriptor) {
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
        if (permittedScope.isEmpty()) {
            return true;
        }
        final Set<String> tokenScopeSet = tokenDescriptor.scopeSet();
        if (tokenScopeSet.isEmpty() || !tokenScopeSet.containsAll(permittedScope)) {
            ctx.setAttr(ERROR_CODE, HttpStatus.FORBIDDEN.code());
            ctx.setAttr(ERROR_TYPE, INSUFFICIENT_SCOPE);
            return false;
        }

        return true;
    }
}

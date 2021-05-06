/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.server.auth.oauth2.OAuth2AuthorizationErrorReporter.forbidden;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.auth.oauth2.OAuth2TokenDescriptor;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AttributeKey;

/**
 * A helper class that allows handling optional validation of the OAuth 2 token within specific execution
 * context (e.g. to implement fine-grained access control).
 */
public final class OAuth2TokenScopeValidator {

    static final AttributeKey<OAuth2TokenDescriptor> OAUTH2_TOKEN = AttributeKey.valueOf("x-oauth2-token");
    static final AttributeKey<String> OAUTH2_REALM = AttributeKey.valueOf("x-oauth2-realm");
    static final String INSUFFICIENT_SCOPE = "insufficient_scope";

    /**
     * Validates given {@link ServiceRequestContext} against permitted scope of the given execution context.
     * This operation assumes that there is a valid {@link OAuth2TokenDescriptor} attached to
     * {@link ServiceRequestContext} by the OAuth 2 subsystem.
     * @param ctx {@link ServiceRequestContext} that contains valid {@link OAuth2TokenDescriptor}.
     * @param permittedScope A {@link Set} of scope tokens (roles) to validate against. This
     *     {@link Set} could be empty, which means that any valid token will be permitted.
     * @return {@code true} if the {@link OAuth2TokenDescriptor} includes non-empty scope, which contains all
     *     elements of the {@code permittedScope}.
     */
    public static boolean validateScope(ServiceRequestContext ctx, Set<String> permittedScope) {
        final OAuth2TokenDescriptor tokenDescriptor = ctx.attr(OAUTH2_TOKEN);
        if (tokenDescriptor == null) {
            return false;
        }
        return validateScope(tokenDescriptor, permittedScope);
    }

    /**
     * Validates given {@link OAuth2TokenDescriptor} against permitted scope of the given execution context.
     * @param tokenDescriptor An instance of {@link OAuth2TokenDescriptor} to validate.
     * @param permittedScope A {@link Set} of scope tokens (roles) to validate against. This
     *     {@link Set} could be empty, which means that any valid token will be permitted.
     * @return {@code true} if the {@link OAuth2TokenDescriptor} includes non-empty scope, which contains all
     *     elements of the {@code permittedScope}.
     */
    public static boolean validateScope(OAuth2TokenDescriptor tokenDescriptor, Set<String> permittedScope) {
        requireNonNull(permittedScope, "permittedScope");
        final Set<String> tokenScopeSet = tokenDescriptor.scopeSet();
        return permittedScope.isEmpty() || tokenScopeSet.containsAll(permittedScope);
    }

    /**
     * Returns an {@link HttpResponse} with {@link HttpStatus#FORBIDDEN} result code and formatted
     * error response as below.
     * <pre>{@code
     *     HTTP/1.1 403 Forbidden
     *     Content-Type: application/json;charset=UTF-8
     *     {"error":"insufficient_scope"}
     * }</pre>
     *
     * <p> This response indicates that the request requires higher privileges than provided by the
     * access token. The resource server SHOULD respond with the HTTP 403 (Forbidden) status code and
     * MAY include the "scope" attribute with the scope necessary to access the protected resource.</p>
     */
    public static HttpResponse insufficientScopeErrorResponse() {
        return forbidden(INSUFFICIENT_SCOPE);
    }

    /**
     * Sets the OAuth 2 token to the request context for optional application-level validation.
     */
    static void setOauth2Context(ServiceRequestContext ctx, OAuth2TokenDescriptor tokenDescriptor,
                                 @Nullable String realm) {
        ctx.setAttr(OAUTH2_TOKEN, tokenDescriptor);
        if (realm != null) {
            ctx.setAttr(OAUTH2_REALM, realm);
        }
    }

    private OAuth2TokenScopeValidator() {}
}

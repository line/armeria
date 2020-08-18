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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.BEARER;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ERROR;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.REALM;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.SCOPE;
import static com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer.ERROR_CODE;
import static com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer.ERROR_TYPE;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthFailureHandler;
import com.linecorp.armeria.server.auth.AuthServiceBuilder;
import com.linecorp.armeria.server.auth.Authorizer;

/**
 * A callback which is invoked to handle OAuth 2.0 authorization failure indicated by {@link Authorizer}.
 * Composes OAuth 2.0 authorization error response in one of the following ways:
 * <ul>
 *    <li>
 *    invalid_request
 *          <p>The request is missing a required parameter, includes an
 *          unsupported parameter or parameter value, repeats the same
 *          parameter, uses more than one method for including an access
 *          token, or is otherwise malformed. The resource server SHOULD
 *          respond with the HTTP 400 (Bad Request) status code.</p>
 *          Example:
 *          <pre>{@code
 *              HTTP/1.1 400 Bad Request
 *              Content-Type: application/json;charset=UTF-8
 *              {"error":"unsupported_token_type"}
 *          }</pre>
 *    </li>
 *    <li>
 *    invalid_token
 *          <p>The access token provided is expired, revoked, malformed, or
 *          invalid for other reasons. The resource SHOULD respond with
 *          the HTTP 401 (Unauthorized) status code. The client MAY
 *          request a new access token and retry the protected resource
 *          request.</p>
 *          Example:
 *          <pre>{@code
 *              HTTP/1.1 401 Unauthorized
 *              WWW-Authenticate: Bearer realm="example",
 *                                error="invalid_token",
 *                                scope="read write"
 *          }</pre>
 *    </li>
 *    <li>
 *    insufficient_scope
 *          <p>The request requires higher privileges than provided by the
 *          access token. The resource server SHOULD respond with the HTTP
 *          403 (Forbidden) status code and MAY include the "scope"
 *          attribute with the scope necessary to access the protected
 *          resource.</p>
 *          Example:
 *          <pre>{@code
 *              HTTP/1.1 403 Forbidden
 *              Content-Type: application/json;charset=UTF-8
 *              {"error":"insufficient_scope"}
 *          }</pre>
 *    </li>
 * </ul>
 *
 * @see AuthServiceBuilder#onFailure(AuthFailureHandler)
 */
class OAuth2AuthorizationFailureHandler implements AuthFailureHandler {

    static final Logger logger = LoggerFactory.getLogger(OAuth2AuthorizationFailureHandler.class);

    @Nullable
    private final String accessTokenType;
    @Nullable
    private final String realm;
    @Nullable
    private final String scope;

    OAuth2AuthorizationFailureHandler(@Nullable String accessTokenType,
                                      @Nullable String realm,
                                      @Nullable String scope) {
        this.accessTokenType = accessTokenType;
        this.realm = realm;
        this.scope = scope;
    }

    /**
     * Invoked when the authorization of the specified {@link HttpRequest} has failed.
     * Composes OAuth 2.0 authorization error response of the following types:
     * <ul>
     *    <li>invalid_request - 400 Bad Request</li>
     *    <li>invalid_token - 401 Unauthorized</li>
     *    <li>insufficient_scope - 403 Forbidden</li>
     * </ul>
     */
    @Override
    public HttpResponse authFailed(HttpService delegate, ServiceRequestContext ctx, HttpRequest req,
                                   @Nullable Throwable cause) throws Exception {
        if (cause != null) {
            if (logger.isWarnEnabled()) {
                logger.warn("Unexpected exception during OAuth 2 authorization.", cause);
            }
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                                   "Unexpected exception during OAuth 2 authorization.");
        }

        final Integer errorCode = ctx.attr(ERROR_CODE);
        final String errorType = ctx.attr(ERROR_TYPE);
        if (errorCode == null) {
            return unauthorized(errorType); // something else happened - do not authorize
        }

        switch (errorCode) {
            case 400:
                if (errorType != null) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8,
                                           "{\"error\":\"%s\"}", errorType); //e.g."unsupported_token_type"
                } else {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST);
                }
            case 401:
                return unauthorized(errorType);
            case 403:
                if (errorType != null) {
                    return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.JSON_UTF_8,
                                           "{\"error\":\"%s\"}", errorType); //e.g."insufficient_scope"
                } else {
                    return HttpResponse.of(HttpStatus.FORBIDDEN);
                }
            default:
                return HttpResponse.of(errorCode);
        }
    }

    private HttpResponse unauthorized(@Nullable String errorType) {
        final Map<String, String> errorFieldsMap = new LinkedHashMap<>(3);
        if (realm != null) {
            errorFieldsMap.put(REALM, realm);
        }
        if (errorType != null) {
            errorFieldsMap.put(ERROR, errorType);
        }
        if (scope != null && !scope.isEmpty()) {
            errorFieldsMap.put(SCOPE, scope);
        }
        final String errorFields = errorFieldsMap.entrySet().stream()
                                                 .map(e -> e.getKey() + "=\"" + e.getValue() + '"')
                                                 .collect(Collectors.joining(", "));
        final String wwwAuthenticateType = accessTokenType == null ? BEARER : accessTokenType;
        final String wwwAuthenticate = errorFields.isEmpty() ?
                wwwAuthenticateType : String.join(" ", wwwAuthenticateType, errorFields);
        final ResponseHeaders responseHeaders =
                ResponseHeaders.of(HttpStatus.UNAUTHORIZED,
                                   HttpHeaderNames.WWW_AUTHENTICATE, wwwAuthenticate);
        return HttpResponse.of(responseHeaders);
    }
}

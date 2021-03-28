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

import static com.linecorp.armeria.server.auth.oauth2.OAuth2AuthorizationErrorReporter.badRequest;
import static com.linecorp.armeria.server.auth.oauth2.OAuth2AuthorizationErrorReporter.forbidden;
import static com.linecorp.armeria.server.auth.oauth2.OAuth2AuthorizationErrorReporter.unauthorized;
import static com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer.ERROR_CODE;
import static com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer.ERROR_TYPE;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
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

    /**
     * Constructs {@link OAuth2AuthorizationFailureHandler}.
     * @param accessTokenType type of the access token, {@code Bearer} used by default
     * @param realm optional security realm of an application or a service
     * @param scope optional JSON string containing a space-separated list of scopes associated with this token,
     *     in the format described at
     *     <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-3.3">[RFC6749], Section 3.3</a>.
     *
     */
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
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6750#rfc.section.3.1">
     *     The OAuth 2.0 Authorization Framework: Bearer Token Usage</a>
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

        // obtain ERROR_CODE and ERROR_TYPE from the context set by OAuth2TokenIntrospectionAuthorizer
        final Integer errorCode = ctx.attr(ERROR_CODE);
        final String errorType = ctx.attr(ERROR_TYPE);
        if (errorCode == null) {
            // something else happened - do not authorize
            // we may omit the expected scope for the generic response
            return unauthorized(errorType, accessTokenType, realm, scope);
        }

        switch (errorCode) {
            case 400:
                return badRequest(errorType);
            case 401:
                return unauthorized(errorType, accessTokenType, realm, scope);
            case 403:
                return forbidden(errorType);
            default:
                return HttpResponse.of(errorCode);
        }
    }
}

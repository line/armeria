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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.BEARER;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ERROR;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.REALM;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.SCOPE;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;

final class OAuth2AuthorizationErrorReporter {

    /**
     * E.g.
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
     */
    static HttpResponse unauthorized(@Nullable String errorType, @Nullable String accessTokenType,
                                     @Nullable String realm, @Nullable String scope) {
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

    /**
     * E.g.
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
     */
    static HttpResponse badRequest(@Nullable String errorType) {
        if (errorType != null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8,
                                   "{\"error\":\"%s\"}", errorType); //e.g."unsupported_token_type"
        } else {
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * E.g.
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
     */
    static HttpResponse forbidden(@Nullable String errorType) {
        if (errorType != null) {
            return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.JSON_UTF_8,
                                   "{\"error\":\"%s\"}", errorType); //e.g."insufficient_scope"
        } else {
            return HttpResponse.of(HttpStatus.FORBIDDEN);
        }
    }

    private OAuth2AuthorizationErrorReporter() {}
}

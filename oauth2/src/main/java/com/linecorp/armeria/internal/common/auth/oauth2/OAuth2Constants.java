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

package com.linecorp.armeria.internal.common.auth.oauth2;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Common OAuth 2.0 constants.
 */
@UnstableApi
public interface OAuth2Constants {

    /**
     * Common Token/Authorization Request constants, as per
     * <a href="https://tools.ietf.org/html/rfc6749">[RFC6749]</a>.
     */
    String GRANT_TYPE = "grant_type";
    String CLIENT_ID = "client_id";
    String USER_NAME = "username";
    String PASSWORD = "password";

    String BEARER = "Bearer";
    String DEFAULT_TOKEN_TYPE = BEARER.toLowerCase();

    String REALM = "realm";

    /**
     * Grant types.
     */
    String CLIENT_CREDENTIALS_GRANT_TYPE = "client_credentials";
    String PASSWORD_GRANT_TYPE = PASSWORD;

    /**
     * Access Token Response constants, as per
     * <a href="https://tools.ietf.org/html/rfc6749#section-5.1">[RFC6749], Section 5.1</a>.
     */
    String ACCESS_TOKEN = "access_token";
    String REFRESH_TOKEN = "refresh_token";
    String SCOPE = "scope";
    String TOKEN_TYPE = "token_type";
    String EXPIRES_IN = "expires_in";

    /**
     * Token Introspection Request constants, as per
     * <a href="https://tools.ietf.org/html/rfc7662#section-2">[RFC7662], Section 2</a>.
     */
    String TOKEN = "token";
    String TOKEN_TYPE_HINT = "token_type_hint";

    /**
     * Token Introspection Response constants, as per
     * <a href="https://tools.ietf.org/html/rfc7662#section-2.2">[RFC7662], Section 2.2</a>.
     */
    String ACTIVE = "active";
    String EXPIRES_AT = "exp";
    String ISSUED_AT = "iat";
    String NOT_BEFORE = "nbf";
    String SUBJECT = "sub";
    String AUDIENCE = "aud";
    String ISSUER = "iss";
    String JWT_ID = "jti";

    /**
     * Error Response constants, as per
     * <a href="https://tools.ietf.org/html/rfc6749#section-5.2">[RFC6749], Section 5.2</a>.
     */
    String ERROR = "error";
    String ERROR_DESCRIPTION = "error_description";
    String ERROR_URI = "error_uri";

    /**
     * Error Response types, as per
     * <a href="https://tools.ietf.org/html/rfc6749#section-5.2">[RFC6749], Section 5.2</a>.
     */
    String INVALID_REQUEST = "invalid_request";
    String INVALID_CLIENT = "invalid_client";
    String INVALID_GRANT = "invalid_grant";
    String UNAUTHORIZED_CLIENT = "unauthorized_client";
    String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    String INVALID_SCOPE = "invalid_scope";

    /**
     * Token Introspection Error Response types, as per
     * <a href="https://tools.ietf.org/html/rfc7009">[RFC7009]</a>.
     */
    String UNSUPPORTED_TOKEN_TYPE = "unsupported_token_type";
}

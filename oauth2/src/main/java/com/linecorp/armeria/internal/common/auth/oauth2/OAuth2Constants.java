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

/**
 * Common OAuth 2.0 constants.
 */
public final class OAuth2Constants {

    /**
     * Common Token/Authorization Request constants, as per
     * <a href="https://datatracker.ietf.org/doc/rfc6749/">[RFC6749]</a>.
     */
    public static final String GRANT_TYPE = "grant_type";
    public static final String CLIENT_ID = "client_id";
    public static final String CLIENT_SECRET = "client_secret";
    public static final String USER_NAME = "username";
    public static final String PASSWORD = "password";

    public static final String BEARER = "Bearer";
    public static final String DEFAULT_TOKEN_TYPE = BEARER.toLowerCase();

    public static final String REALM = "realm";

    /**
     * Grant types.
     */
    public static final String CLIENT_CREDENTIALS_GRANT_TYPE = "client_credentials";
    public static final String PASSWORD_GRANT_TYPE = PASSWORD;

    /**
     * Access Token Response constants, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-5.1">[RFC6749], Section 5.1</a>.
     */
    public static final String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String SCOPE = "scope";
    public static final String TOKEN_TYPE = "token_type";
    public static final String EXPIRES_IN = "expires_in";

    /**
     * Token Introspection Request constants, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc7662#section-2">[RFC7662], Section 2</a>.
     */
    public static final String TOKEN = "token";
    public static final String TOKEN_TYPE_HINT = "token_type_hint";

    /**
     * Token Introspection Response constants, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc7662#section-2.2">[RFC7662], Section 2.2</a>.
     */
    public static final String ACTIVE = "active";
    public static final String EXPIRES_AT = "exp";
    public static final String ISSUED_AT = "iat";
    public static final String NOT_BEFORE = "nbf";
    public static final String SUBJECT = "sub";
    public static final String AUDIENCE = "aud";
    public static final String ISSUER = "iss";
    public static final String JWT_ID = "jti";

    /**
     * Error Response constants, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-5.2">[RFC6749], Section 5.2</a>.
     */
    public static final String ERROR = "error";
    public static final String ERROR_DESCRIPTION = "error_description";
    public static final String ERROR_URI = "error_uri";

    /**
     * Error Response types, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-5.2">[RFC6749], Section 5.2</a>.
     */
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String INVALID_CLIENT = "invalid_client";
    public static final String INVALID_GRANT = "invalid_grant";
    public static final String UNAUTHORIZED_CLIENT = "unauthorized_client";
    public static final String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    public static final String INVALID_SCOPE = "invalid_scope";

    /**
     * Token Introspection Error Response types, as per
     * <a href="https://datatracker.ietf.org/doc/rfc7009/">[RFC7009]</a>.
     */
    public static final String UNSUPPORTED_TOKEN_TYPE = "unsupported_token_type";

    /**
     * JWT constants, as per <a href="https://datatracker.ietf.org/doc/html/rfc7523>[RFC7523]</a>.
     */
    public static final String JWT_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    public static final String JWT_ASSERTION = "assertion";
    public static final String CLIENT_ASSERTION = "client_assertion";
    public static final String CLIENT_ASSERTION_TYPE = "client_assertion_type";
    public static final String CLIENT_ASSERTION_TYPE_JWT =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private OAuth2Constants() {}
}

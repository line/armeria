/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client.auth.oauth2;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.common.auth.oauth2.OAuth2Request;

/**
 * An OAuth 2.0 request to obtain an access token.
 */
@UnstableApi
public interface AccessTokenRequest extends OAuth2Request {

    /**
     * Creates a new {@link AccessTokenRequest} that is used to request an access token using only the client
     * credentials, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-4.4">RFC 6749, Section 4.4</a>.
     */
    static AccessTokenRequest ofClientCredentials(ClientAuthentication clientAuthentication) {
        requireNonNull(clientAuthentication, "clientAuthentication");
        return new ClientCredentialsAccessTokenRequest(clientAuthentication, null);
    }

    /**
     * Creates a new {@link AccessTokenRequest} that is used to request an access token using the client
     * credentials and the scopes, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-4.4">RFC 6749, Section 4.4</a>.
     */
    static AccessTokenRequest ofClientCredentials(ClientAuthentication clientAuthentication,
                                                  List<String> scopes) {
        requireNonNull(clientAuthentication, "clientAuthentication");
        requireNonNull(scopes, "scopes");
        return new ClientCredentialsAccessTokenRequest(clientAuthentication, scopes);
    }

    /**
     * Creates a new {@link AccessTokenRequest} that is used to request an access token using the client ID
     * and client secret, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-4.4">RFC 6749, Section 4.4</a>.
     * The specified client ID and client secret will be encoded and sent as the HTTP Basic authentication
     * header.
     */
    static AccessTokenRequest ofClientCredentials(String clientId, String clientSecret) {
        return ofClientCredentials(ClientAuthentication.ofClientPassword(clientId, clientSecret));
    }

    /**
     * Creates a new {@link AccessTokenRequest} that is used to request an access token using the resource owner
     * password credentials, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-4.3">RFC 6749, Section 4.3</a>.
     */
    static AccessTokenRequest ofResourceOwnerPassword(String username, String password) {
        return ofResourceOwnerPassword(username, password, null, null);
    }

    /**
     * Creates a new {@link AccessTokenRequest} that is used to request an access token using the resource owner
     * password credentials with the {@link ClientAuthentication}, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-4.3">RFC 6749, Section 4.3</a>.
     */
    static AccessTokenRequest ofResourceOwnerPassword(String username, String password,
                                                      ClientAuthentication clientAuthentication) {
        requireNonNull(clientAuthentication, "clientAuthentication");
        return ofResourceOwnerPassword(username, password, clientAuthentication, null);
    }

    /**
     * Creates a new {@link AccessTokenRequest} that is used to request an access token using the resource owner
     * password credentials with the {@link ClientAuthentication}, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-4.3">RFC 6749, Section 4.3</a>.
     */
    static AccessTokenRequest ofResourceOwnerPassword(String username, String password,
                                                      @Nullable ClientAuthentication clientAuthentication,
                                                      @Nullable List<String> scopes) {
        requireNonNull(username, "username");
        requireNonNull(password, "password");
        return new ResourceOwnerPasswordAccessTokenRequest(username, password, clientAuthentication, scopes);
    }

    /**
     * Creates a new {@link AccessTokenRequest} that is used to request an access token using the JSON Web Token
     * (JWT), as per <a href="https://datatracker.ietf.org/doc/html/rfc7523">RFC 7523</a>.
     */
    static AccessTokenRequest ofJsonWebToken(String jsonWebToken) {
        return ofJsonWebToken(jsonWebToken, null, null);
    }

    /**
     * Creates a new {@link AccessTokenRequest} that is used to request an access token using the JSON Web Token
     * (JWT), as per <a href="https://datatracker.ietf.org/doc/html/rfc7523">RFC 7523</a>.
     */
    static AccessTokenRequest ofJsonWebToken(String jsonWebToken,
                                             ClientAuthentication clientAuthentication) {
        requireNonNull(clientAuthentication, "clientAuthentication");
        return ofJsonWebToken(jsonWebToken, clientAuthentication, null);
    }

    /**
     * Creates a new {@link AccessTokenRequest} that is used to request an access token using the JSON Web Token
     * (JWT), as per <a href="https://datatracker.ietf.org/doc/html/rfc7523">RFC 7523</a>.
     */
    static AccessTokenRequest ofJsonWebToken(String jsonWebToken,
                                             @Nullable ClientAuthentication clientAuthentication,
                                             @Nullable List<String> scopes) {
        requireNonNull(jsonWebToken, "jsonWebToken");
        // TODO(ikhoon): Consider providing a builder for creating JSON Web Token (JWT).
        return new JsonWebTokenAccessTokenRequest(jsonWebToken, clientAuthentication, scopes);
    }

    /**
     * Returns the grant type of the access request.
     */
    String grantType();

    /**
     * Returns the scopes of the access request, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-3.3">RFC 6749, Section 3.3</a>.
     */
    @Nullable
    List<String> scopes();
}

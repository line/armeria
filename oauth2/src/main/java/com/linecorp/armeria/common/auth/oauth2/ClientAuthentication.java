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

package com.linecorp.armeria.common.auth.oauth2;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Provides client authentication for the OAuth 2.0 requests,
 * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
 * For example:
 * <pre>{@code
 * Authorization: Basic czZCaGRSa3F0Mzo3RmpmcDBaQnIxS3REUmJuZlZkbUl3
 * }</pre>
 */
@UnstableApi
public interface ClientAuthentication {

    /**
     * Returns a {@link ClientAuthentication} for the given client ID and client secret, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3.1">[RFC6749], Section 2.3.1</a>.
     * The {@link ClientAuthentication} will be encoded as the HTTP Basic authentication.
     */
    static ClientAuthentication ofClientPassword(String clientId, String clientSecret) {
        return ofClientPassword(clientId, clientSecret, true);
    }

    /**
     * Returns a {@link ClientAuthentication} for the given client ID and client secret, as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3.1">[RFC6749], Section 2.3.1</a>.
     *
     * <p>Note that if {@code useBasicAuth} is false, the client ID and client secret will be added as body
     * parameters.
     */
    static ClientAuthentication ofClientPassword(String clientId, String clientSecret, boolean useBasicAuth) {
        requireNonNull(clientId, "clientId");
        requireNonNull(clientSecret, "clientSecret");
        return new ClientPasswordClientAuthentication(clientId, clientSecret, useBasicAuth);
    }

    /**
     * Returns a {@link ClientAuthentication} for the given JSON Web Token (JWT), as per
     * <a href="https://datatracker.ietf.org/doc/html/rfc7523#section-2.2">[RFC7523], Section 2.2</a>.
     */
    static ClientAuthentication ofJsonWebToken(String jsonWebToken) {
        requireNonNull(jsonWebToken, "jsonWebToken");
        return new JsonWebTokenClientAuthentication(jsonWebToken);
    }

    /**
     * Returns a {@link ClientAuthentication} for the OAuth 2.0 requests based on encoded authorization token
     * and authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationScheme One of the registered HTTP authentication schemes as per
     *                            <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                            HTTP Authentication Scheme Registry</a>.
     * @param authorization A client authorization token.
     */
    static ClientAuthentication ofAuthorization(String authorizationScheme, String authorization) {
        requireNonNull(authorizationScheme, "authorizationType");
        requireNonNull(authorization, "authorization");
        return new AnyAuthorizationSchemeClientAuthentication(authorizationScheme, authorization);
    }

    /**
     * Returns a {@link ClientAuthentication} for the OAuth 2.0 requests based on encoded HTTP basic
     * authorization token, as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorization A client authorization token.
     */
    static ClientAuthentication ofBasic(String authorization) {
        requireNonNull(authorization, "authorization");
        return ofAuthorization("Basic", authorization);
    }

    /**
     * Sets this {@link ClientAuthentication} as headers.
     *
     * <p>The client authentication can be set via the given {@link HttpHeadersBuilder}
     * or {@link #addAsBodyParams(QueryParamsBuilder)}.
     */
    void addAsHeaders(HttpHeadersBuilder headersBuilder);

    /**
     * Returns this {@link ClientAuthentication} as {@link HttpHeaders}.
     */
    default HttpHeaders asHeaders() {
        final HttpHeadersBuilder headersBuilder = HttpHeaders.builder();
        addAsHeaders(headersBuilder);
        return headersBuilder.build();
    }

    /**
     * Sets this {@link ClientAuthentication} as body parameters.
     *
     * <p>The client authentication can be set via the given {@link QueryParamsBuilder}
     * or {@link #addAsHeaders(HttpHeadersBuilder)}.
     */
    void addAsBodyParams(QueryParamsBuilder formBuilder);

    /**
     * Returns this {@link ClientAuthentication} as body parameters.
     */
    default QueryParams asBodyParams() {
        final QueryParamsBuilder formBuilder = QueryParams.builder();
        addAsBodyParams(formBuilder);
        return formBuilder.build();
    }
}

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

package com.linecorp.armeria.internal.client.auth.oauth2;

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.CLIENT_CREDENTIALS_GRANT_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.GRANT_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.SCOPE;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.TokenRequestException;
import com.linecorp.armeria.common.auth.oauth2.UnsupportedMediaTypeException;

/**
 * Implements Client Credentials Grant request/response flow,
 * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-4.4">[RFC6749], Section 4.4</a>.
 */
@UnstableApi
public final class ClientCredentialsTokenRequest extends AbstractAccessTokenRequest {

    /**
     * Implements Client Credentials Grant request/response flow,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-4.4">[RFC6749], Section 4.4</a>.
     *
     * @param accessTokenEndpoint A {@link WebClient} to facilitate an Access Token request. Must correspond to
     *                            the Access Token endpoint of the OAuth 2 system.
     * @param accessTokenEndpointPath A URI path that corresponds to the Access Token endpoint of the
     *                                OAuth 2 system.
     * @param clientAuthorization Provides client authorization for the OAuth requests,
     *                            as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     */
    public ClientCredentialsTokenRequest(WebClient accessTokenEndpoint, String accessTokenEndpointPath,
                                         ClientAuthorization clientAuthorization) {
        super(accessTokenEndpoint, accessTokenEndpointPath,
              // client authorization is MANDATORY for this type of grant
              requireNonNull(clientAuthorization, "clientAuthorization"));
    }

    /**
     * Makes Client Credentials Grant request and handles the response converting the result data
     * to {@link GrantedOAuth2AccessToken}.
     * @param scope OPTIONAL. Scope to request for the token. A list of space-delimited,
     *              case-sensitive strings. The strings are defined by the authorization server.
     *              The authorization server MAY fully or partially ignore the scope requested by the
     *              client, based on the authorization server policy or the resource owner's
     *              instructions. If the issued access token scope is different from the one requested
     *              by the client, the authorization server MUST include the "scope" response
     *              parameter to inform the client of the actual scope granted.
     *              If the client omits the scope parameter when requesting authorization, the
     *              authorization server MUST either process the request using a pre-defined default
     *              value or fail the request indicating an invalid scope.
     * @return A {@link CompletableFuture} carrying the target result as {@link GrantedOAuth2AccessToken}.
     * @throws TokenRequestException when the endpoint returns {code HTTP 400 (Bad Request)} status and the
     *                               response payload contains the details of the error.
     * @throws InvalidClientException when the endpoint returns {@code HTTP 401 (Unauthorized)} status, which
     *                                typically indicates that client authentication failed (e.g.: unknown
     *                                client, no client authentication included, or unsupported authentication
     *                                method).
     * @throws UnsupportedMediaTypeException if the media type of the response does not match the expected
     *                                       (JSON).
     */
    public CompletableFuture<GrantedOAuth2AccessToken> make(@Nullable String scope) {
        final QueryParamsBuilder requestFormBuilder = QueryParams.builder();

        // populate request form data
        // MANDATORY grant_type
        requestFormBuilder.add(GRANT_TYPE, CLIENT_CREDENTIALS_GRANT_TYPE);
        // OPTIONAL scope
        if (scope != null) {
            requestFormBuilder.add(SCOPE, scope);
        }
        // this grant uses client credentials supplied in the {@link HttpHeaderNames#AUTHORIZATION} header,
        // so no other request parameters required here

        // make actual access token request
        return executeWithParameters(requestFormBuilder.build());
    }
}

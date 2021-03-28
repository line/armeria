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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ACCESS_TOKEN;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.TOKEN;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.TOKEN_TYPE_HINT;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.TokenRequestException;
import com.linecorp.armeria.common.auth.oauth2.UnsupportedMediaTypeException;

/**
 * A common abstraction for the requests implementing various Token operations request/response flows,
 * such as Token Introspection flow
 * (<a href="https://datatracker.ietf.org/doc/html/rfc7662#section-2">[RFC7662], Section 2</a>),
 * and Token Revocation flow (<a href="https://datatracker.ietf.org/doc/rfc7009/">[RFC7009]</a>).
 * @param <T> the type of the authorization result.
 */
@UnstableApi
public abstract class AbstractTokenOperationRequest<T> extends AbstractOAuth2Request<T> {

    /**
     * A common abstraction for the requests implementing various Token operations request/response flows,
     * such as Token Introspection flow
     * (<a href="https://datatracker.ietf.org/doc/html/rfc7662#section-2">[RFC7662], Section 2</a>),
     * and Token Revocation flow (<a href="https://datatracker.ietf.org/doc/rfc7009/">[RFC7009]</a>).
     *
     * @param operationsEndpoint A {@link WebClient} to facilitate the Token Operations requests. Must
     *                           correspond to the required Token Operations endpoint of the OAuth 2 system.
     * @param operationsEndpointPath A URI path that corresponds to the token Operations endpoint of the
     *                               OAuth 2 system.
     * @param clientAuthorization Provides client authorization for the OAuth requests,
     *                            as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     */
    protected AbstractTokenOperationRequest(WebClient operationsEndpoint, String operationsEndpointPath,
                                            @Nullable ClientAuthorization clientAuthorization) {
        super(operationsEndpoint, operationsEndpointPath, clientAuthorization);
    }

    /**
     * Makes a token operations (Introspection/Revocation) request using given {@code token} and
     * optional {@code tokenType}.
     * @param token A token this operation request applies to.
     * @param tokenType A hint about the type of the token submitted for (Introspection/Revocation) operation.
     *                  Either {@code access_token} or {@code refresh_token} as per
     *                  <a href="https://datatracker.ietf.org/doc/html/rfc7009#section-2.1">[RFC7009], Section 2.1</a>.
     * @return A {@link CompletableFuture} carrying the target result.
     * @throws TokenRequestException when the endpoint returns {code HTTP 400 (Bad Request)} status and the
     *                               response payload contains the details of the error.
     * @throws InvalidClientException when the endpoint returns {@code HTTP 401 (Unauthorized)} status, which
     *                                typically indicates that client authentication failed (e.g.: unknown
     *                                client, no client authentication included, or unsupported authentication
     *                                method).
     * @throws UnsupportedMediaTypeException if the media type of the response does not match the expected
     *                                       (JSON).
     */
    public CompletableFuture<T> make(String token, @Nullable String tokenType) {
        requireNonNull(token, TOKEN);
        final QueryParamsBuilder requestFormBuilder = QueryParams.builder();
        // populate request form data
        // MANDATORY token
        requestFormBuilder.add(TOKEN, token);
        // OPTIONAL token_type_hint
        if (tokenType != null) {
            requestFormBuilder.add(TOKEN_TYPE_HINT, tokenType);
        }

        // make actual operational request
        return executeWithParameters(requestFormBuilder.build());
    }

    /**
     * Makes a token operations (Introspection/Revocation) request using given {@code accessToken}.
     * @param accessToken A token this operation request applies to.
     * @return A {@link CompletableFuture} carrying the target result.
     * @throws TokenRequestException when the endpoint returns {code HTTP 400 (Bad Request)} status and the
     *                               response payload contains the details of the error.
     * @throws InvalidClientException when the endpoint returns {@code HTTP 401 (Unauthorized)} status, which
     *                                typically indicates that client authentication failed (e.g.: unknown
     *                                client, no client authentication included, or unsupported authentication
     *                                method).
     * @throws UnsupportedMediaTypeException if the media type of the response does not match the expected
     *                                       (JSON).
     */
    public CompletableFuture<T> make(String accessToken) {
        return make(accessToken, ACCESS_TOKEN);
    }
}

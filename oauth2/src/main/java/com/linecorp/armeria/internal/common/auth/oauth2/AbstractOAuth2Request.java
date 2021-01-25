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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.TokenRequestException;
import com.linecorp.armeria.common.auth.oauth2.UnsupportedMediaTypeException;
import com.linecorp.armeria.common.auth.oauth2.UnsupportedResponseException;

/**
 * A common abstraction for the requests implementing various OAuth 2.0 request/response flows,
 * as per <a href="https://tools.ietf.org/html/rfc6749">[RFC6749]</a> and other relevant specifications.
 * @param <T> the type of the authorization result.
 */
@UnstableApi
public abstract class AbstractOAuth2Request<T> {

    private final WebClient endpoint;
    private final String endpointPath;
    @Nullable
    private final ClientAuthorization clientAuthorization;

    /**
     * A common abstraction for the requests implementing various Authorization request/response flows,
     * as per <a href="https://tools.ietf.org/html/rfc6749">[RFC6749]</a> and other relevant specifications.
     *
     * @param endpoint A {@link WebClient} to facilitate the Authorization requests. Must
     *                 correspond to the required Authorization endpoint of the OAuth 2 system.
     * @param endpointPath A URI path that corresponds to the Authorization endpoint of the
     *                     OAuth 2 system.
     * @param clientAuthorization Provides client authorization for the OAuth requests,
     *                            as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     */
    protected AbstractOAuth2Request(WebClient endpoint, String endpointPath,
                                    @Nullable ClientAuthorization clientAuthorization) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
        this.endpointPath = requireNonNull(endpointPath, "endpointPath");
        this.clientAuthorization = clientAuthorization; // optional
    }

    /**
     * Returns the {@link WebClient} of the authorization endpoint.
     */
    protected final WebClient endpoint() {
        return endpoint;
    }

    /**
     * Returns the authorization endpoint path.
     */
    protected final String endpointPath() {
        return endpointPath;
    }

    /**
     * Returns the client authorization object.
     */
    @Nullable
    protected final ClientAuthorization clientAuthorization() {
        return clientAuthorization;
    }

    /**
     * Extracts data from OK response and converts it to the target type {@code T}.
     */
    protected abstract T extractOkResults(AggregatedHttpResponse response, QueryParams requestFormData);

    /**
     * Returns the value for the {@link HttpHeaderNames#AUTHORIZATION}.
     */
    @Nullable
    protected final String authorizationHeaderValue() {
        return clientAuthorization == null ? null : clientAuthorization.asHeaderValue();
    }

    /**
     * Sets client credentials as form data parameters,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     */
    protected final void addCredentialsAsBodyParameters(QueryParamsBuilder formBuilder) {
        if (clientAuthorization != null) {
            clientAuthorization.addAsBodyParameters(formBuilder);
        }
    }

    /**
     * Makes a request to the authorization endpoint using supplied {@code requestForm} parameters and converts
     * the result to the given type {@code T}.
     */
    protected CompletableFuture<T> executeWithParameters(QueryParams requestFormData) {
        final HttpResponse response = endpoint().execute(createHttpRequest(endpointPath, requestFormData));
        // when response aggregated, then extract the results...
        return response.aggregate().thenApply(r -> extractResults(r, requestFormData));
    }

    /**
     * Produces {@link HttpRequest} based on this object.
     */
    private HttpRequest createHttpRequest(String endpointPath, QueryParams requestFormData) {
        final RequestHeadersBuilder headersBuilder =
                RequestHeaders.builder(HttpMethod.POST, endpointPath);
        final String authorizationHeaderValue = authorizationHeaderValue();
        if (authorizationHeaderValue != null) {
            headersBuilder.add(HttpHeaderNames.AUTHORIZATION, authorizationHeaderValue);
        } else {
            requestFormData = requestFormData.withMutations(this::addCredentialsAsBodyParameters);
        }
        headersBuilder.addObject(HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA);

        return HttpRequest.of(headersBuilder.build(), HttpData.ofUtf8(requestFormData.toQueryString()));
    }

    /**
     * Extracts the result and convert it to the target type {@code T} or throw an error in case of an error
     * result.
     * @param response An {@link AggregatedHttpResponse} returned by the authorization endpoint.
     * @param requestFormData A {@link QueryParams} that contains all the elements of the request form sent with
     *                        the request.
     * @throws TokenRequestException when the endpoint returns {code HTTP 400 (Bad Request)} status and the
     *                               response payload contains the details of the error.
     * @throws InvalidClientException when the endpoint returns {@code HTTP 401 (Unauthorized)} status, which
     *                                typically indicates that client authentication failed (e.g.: unknown
     *                                client, no client authentication included, or unsupported authentication
     *                                method).
     * @throws UnsupportedMediaTypeException if the media type of the response does not match the expected
     *                                       (JSON).
     */
    @Nullable
    protected T extractResults(AggregatedHttpResponse response, QueryParams requestFormData) {
        final HttpStatus status = response.status();
        switch (status.code()) {
            case 200: // OK
                // expected Content-Type: application/json;charset=UTF-8
                validateContentType(response, MediaType.JSON);
                return extractOkResults(response, requestFormData);
            case 400: // Bad Request
                // expected Content-Type: application/json;charset=UTF-8
                validateContentType(response, MediaType.JSON);
                throw onBadRequestError(response);
            case 401: // Unauthorized
                throw onUnauthorizedError(response);
        }
        throw new UnsupportedResponseException(status, response.contentUtf8());
    }

    /**
     * Composes {@link TokenRequestException} upon 400 Bad Request response
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-5.2">[RFC6749], Section 5.2</a>.
     * @param errorResponse response received from the server
     * @return an instance of {@link TokenRequestException}
     */
    protected TokenRequestException onBadRequestError(AggregatedHttpResponse errorResponse) {
        return TokenRequestException.parse(errorResponse.contentUtf8());
    }

    /**
     * Composes {@link InvalidClientException} upon 401 Unauthorized response
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-5.2">[RFC6749], Section 5.2</a> (invalid_client).
     * @param errorResponse response received from the server
     * @return an instance of {@link InvalidClientException}
     */
    protected TokenRequestException onUnauthorizedError(AggregatedHttpResponse errorResponse) {
        final StringBuilder messageBuilder = new StringBuilder().append(errorResponse.status());
        final String wwwAuthenticate = errorResponse.headers().get(HttpHeaderNames.WWW_AUTHENTICATE);
        if (wwwAuthenticate != null) {
            messageBuilder.append(": ").append(wwwAuthenticate);
        }
        final HttpData errorResponseContents = errorResponse.content();
        if (!errorResponseContents.isEmpty()) {
            messageBuilder.append(": ").append(errorResponseContents.toStringUtf8());
        }
        return new InvalidClientException(messageBuilder.toString(), null);
    }

    /**
     * Validates the content type of the response.
     */
    private static void validateContentType(AggregatedHttpResponse response, MediaType expectedType) {
        final MediaType contentType = response.contentType();
        if (contentType == null) {
            // if omitted, assume that the type matches the expected
            return;
        }
        final String mediaType = contentType.nameWithoutParameters();
        if (!mediaType.equalsIgnoreCase(expectedType.nameWithoutParameters())) {
            throw new UnsupportedMediaTypeException(mediaType,
                                                    response.status().toString(), response.contentUtf8());
        }
    }
}

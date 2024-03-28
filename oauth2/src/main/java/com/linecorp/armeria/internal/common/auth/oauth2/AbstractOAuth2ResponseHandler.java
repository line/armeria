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

package com.linecorp.armeria.internal.common.auth.oauth2;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.OAuth2ResponseHandler;
import com.linecorp.armeria.common.auth.oauth2.TokenRequestException;
import com.linecorp.armeria.common.auth.oauth2.UnsupportedMediaTypeException;
import com.linecorp.armeria.common.auth.oauth2.UnsupportedResponseException;

public abstract class AbstractOAuth2ResponseHandler<T> implements OAuth2ResponseHandler<T> {

    @Override
    public final T handle(AggregatedHttpResponse response, QueryParams requestParams) {
        final HttpStatus status = response.status();
        switch (status.code()) {
            case 200: // OK
                // expected Content-Type: application/json;charset=UTF-8
                validateContentType(response, MediaType.JSON);
                return handleOkResult(response, requestParams);
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
     * Handles the 200 OK OAuth 2.0 response.
     */
    protected abstract T handleOkResult(AggregatedHttpResponse response, QueryParams requestParams);

    /**
     * Composes {@link TokenRequestException} upon 400 Bad Request response
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-5.2">[RFC6749], Section 5.2</a>.
     *
     * @param errorResponse response received from the server
     *
     * @return an instance of {@link TokenRequestException}
     */
    private static TokenRequestException onBadRequestError(AggregatedHttpResponse errorResponse) {
        return TokenRequestException.parse(errorResponse.contentUtf8());
    }

    /**
     * Composes {@link InvalidClientException} upon 401 Unauthorized response
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-5.2">[RFC6749], Section 5.2</a> (invalid_client).
     *
     * @param errorResponse response received from the server
     *
     * @return an instance of {@link InvalidClientException}
     */
    private static TokenRequestException onUnauthorizedError(AggregatedHttpResponse errorResponse) {
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

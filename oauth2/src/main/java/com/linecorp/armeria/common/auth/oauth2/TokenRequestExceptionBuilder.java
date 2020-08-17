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

package com.linecorp.armeria.common.auth.oauth2;

import java.util.LinkedHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A builder of a {@link TokenRequestException}.
 */
final class TokenRequestExceptionBuilder {

    private static final String ERROR = "error";
    private static final String ERROR_DESCRIPTION = "error_description";
    private static final String ERROR_URI = "error_uri";

    // RFC6749 (The OAuth 2.0 Authorization Framework) - https://tools.ietf.org/html/rfc6749#section-5.2
    private static final String INVALID_REQUEST = "invalid_request";
    private static final String INVALID_CLIENT = "invalid_client";
    private static final String INVALID_GRANT = "invalid_grant";
    private static final String UNAUTHORIZED_CLIENT = "unauthorized_client";
    private static final String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    private static final String INVALID_SCOPE = "invalid_scope";

    // RFC7009 (OAuth 2.0 Token Revocation) - https://tools.ietf.org/html/rfc7009
    private static final String UNSUPPORTED_TOKEN_TYPE = "unsupported_token_type";

    static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE =
            new TypeReference<LinkedHashMap<String, String>>() {};

    private TokenRequestExceptionBuilder() {
    }

    /**
     * Parses {@code JSON} error response body and created a new instance of {@link TokenRequestException}
     * using the response data.
     * @param rawResponse {@code JSON} formatted error response body.
     * @return a new instance of {@link TokenRequestException}
     */
    static TokenRequestException parse(String rawResponse) {
        final LinkedHashMap<String, String> map;
        try {
            map = JSON.readValue(rawResponse, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        final String errorDescription = map.get(ERROR_DESCRIPTION);
        final String errorUri = map.get(ERROR_URI);
        final String errorType = map.get(ERROR);
        if (errorType != null) {
            switch (errorType.toLowerCase()) {
                case INVALID_REQUEST:
                    return new InvalidRequestException(errorDescription, errorUri);
                case INVALID_CLIENT:
                    return new InvalidClientException(errorDescription, errorUri);
                case INVALID_GRANT:
                    return new InvalidGrantException(errorDescription, errorUri);
                case UNAUTHORIZED_CLIENT:
                    return new UnauthorizedClientException(errorDescription, errorUri);
                case UNSUPPORTED_GRANT_TYPE:
                    return new UnsupportedGrantTypeException(errorDescription, errorUri);
                case INVALID_SCOPE:
                    return new InvalidScopeException(errorDescription, errorUri);
                case UNSUPPORTED_TOKEN_TYPE:
                    return new UnsupportedTokenTypeException(errorDescription, errorUri);
            }
        }
        return new TokenRequestException(errorDescription, errorUri);
    }
}

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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ERROR;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ERROR_DESCRIPTION;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ERROR_URI;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.INVALID_CLIENT;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.INVALID_GRANT;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.INVALID_REQUEST;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.INVALID_SCOPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.UNAUTHORIZED_CLIENT;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.UNSUPPORTED_GRANT_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.UNSUPPORTED_TOKEN_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.ResponseParserUtil.JSON;

import java.util.LinkedHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A common token request exception type.
 */
@UnstableApi
public class TokenRequestException extends RuntimeException {

    private static final long serialVersionUID = 3324433572773111913L;

    private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE =
            new TypeReference<LinkedHashMap<String, String>>() {};

    /**
     * Parses {@code JSON} error response body and created a new instance of {@link TokenRequestException}
     * using the response data. Returns an error-specific type of {@link TokenRequestException}.
     * @param rawResponse {@code JSON} formatted error response body.
     * @return a new instance of {@link TokenRequestException}
     */
    public static TokenRequestException parse(String rawResponse) {
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

    @Nullable
    private final String errorUri;

    /**
     * Constructs a new {@link TokenRequestException} using {@code errorDescription} and {@code errorUri}.
     * @param errorDescription OPTIONAL. Human-readable ASCII [USASCII] text providing additional information,
     *                         used to assist the client developer in understanding the error that occurred.
     *                         Values for the {@code errorDescription} parameter MUST NOT include
     *                         characters outside the set {@code %x20-21} / {@code %x23-5B} / {@code %x5D-7E}.
     * @param errorUri OPTIONAL. A URI identifying a human-readable web page with information about the error,
     *                 used to provide the client developer with additional information about the error.
     *                 Values for the {@code errorUri} parameter MUST conform to the URI-reference syntax and
     *                 thus MUST NOT include characters outside
     *                 the set {@code %x21} / {@code %x23-5B} / {@code %x5D-7E}.
     */
    public TokenRequestException(String errorDescription, @Nullable String errorUri) {
        super(errorDescription);
        this.errorUri = errorUri;
    }

    /**
     * Constructs a new {@link TokenRequestException} using {@code errorDescription} and {@code errorUri}.
     * @param errorDescription OPTIONAL. Human-readable ASCII [USASCII] text providing additional information,
     *                         used to assist the client developer in understanding the error that occurred.
     *                         Values for the {@code errorDescription} parameter MUST NOT include
     *                         characters outside the set {@code %x20-21} / {@code %x23-5B} / {@code %x5D-7E}.
     * @param errorUri OPTIONAL. A URI identifying a human-readable web page with information about the error,
     *                 used to provide the client developer with additional information about the error.
     *                 Values for the {@code errorUri} parameter MUST conform to the URI-reference syntax and
     *                 thus MUST NOT include characters outside
     *                 the set {@code %x21} / {@code %x23-5B} / {@code %x5D-7E}.
     * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method).
     *              (A {@code null} value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public TokenRequestException(String errorDescription, @Nullable String errorUri, Throwable cause) {
        super(errorDescription, cause);
        this.errorUri = errorUri;
    }

    /**
     * A URI identifying a human-readable web page with information about the error, used to provide the client
     * developer with additional information about the error.
     * Values for the {@code errorUri} parameter MUST conform to the URI-reference syntax and thus MUST NOT
     * include characters outside the set {@code %x21} / {@code %x23-5B} / {@code %x5D-7E}.
     */
    @Nullable
    public final String getErrorUri() {
        return errorUri;
    }
}

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

import static com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken.ISSUED_AT;
import static com.linecorp.armeria.common.auth.oauth2.OAuth2TokenDescriptor.SCOPE_SEPARATOR;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ACCESS_TOKEN;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.EXPIRES_IN;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.REFRESH_TOKEN;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.SCOPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.TOKEN_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.ResponseParserUtil.JSON;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants;

/**
 * Builds an instance of {@link GrantedOAuth2AccessToken}.
 */
@UnstableApi
public final class GrantedOAuth2AccessTokenBuilder {

    private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE =
            new TypeReference<LinkedHashMap<String, String>>() {};

    static GrantedOAuth2AccessToken of(String rawResponse, @Nullable String requestScope) {

        final LinkedHashMap<String, String> map;
        try {
            map = JSON.readValue(requireNonNull(rawResponse, "rawResponse"), MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        final GrantedOAuth2AccessTokenBuilder builder =
                new GrantedOAuth2AccessTokenBuilder(requireNonNull(map.remove(ACCESS_TOKEN), ACCESS_TOKEN));

        final String tokenType = map.remove(TOKEN_TYPE);
        if (tokenType != null) {
            builder.tokenType(tokenType);
        }
        final String issuedAt = map.remove(ISSUED_AT);
        if (issuedAt != null) {
            builder.issuedAt(ISO_INSTANT.parse(issuedAt, Instant::from));
        }
        final String expiresIn = map.remove(EXPIRES_IN);
        if (expiresIn != null) {
            builder.expiresIn(Duration.ofSeconds(Long.parseLong(expiresIn)));
        }
        final String refreshToken = map.remove(REFRESH_TOKEN);
        if (refreshToken != null) {
            builder.refreshToken(refreshToken);
        }
        String scope = map.remove(SCOPE);
        if (scope == null) {
            scope = requestScope;
        }
        if (scope != null) {
            builder.scope(scope.split(SCOPE_SEPARATOR));
        }
        builder.extras(map);

        builder.rawResponse(rawResponse);

        return builder.build();
    }

    private final String accessToken;

    @Nullable
    private String tokenType;

    @Nullable
    private Duration expiresIn;

    @Nullable
    private String refreshToken;

    private final ImmutableList.Builder<String> scope = ImmutableList.builder();

    private final ImmutableMap.Builder<String, String> extras = ImmutableMap.builder();

    @Nullable
    private Instant issuedAt;

    @Nullable
    private String rawResponse;

    /**
     * Constructs a new instance of {@link GrantedOAuth2AccessTokenBuilder} given the mandatory value
     * {@value OAuth2Constants#ACCESS_TOKEN} of the access token issued by the authorization server.
     * @param accessToken {@value OAuth2Constants#ACCESS_TOKEN} Access Token response field,
     *                    REQUIRED. The access token issued by the authorization server.
     */
    GrantedOAuth2AccessTokenBuilder(String accessToken) {
        this.accessToken = requireNonNull(accessToken, "accessToken");
    }

    /**
     * {@value OAuth2Constants#TOKEN_TYPE} Access Token response field,
     * REQUIRED. The type of the token issued as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-7.1">[RFC6749], Section 7.1</a>.
     * Value is case insensitive.
     */
    public GrantedOAuth2AccessTokenBuilder tokenType(String tokenType) {
        this.tokenType = requireNonNull(tokenType, "tokenType");
        return this;
    }

    /**
     * {@value OAuth2Constants#EXPIRES_IN} Access Token response field,
     * RECOMMENDED. {@link Duration} indicating the lifetime of the access token. For example,
     * the value 3600 seconds denotes that the access token will expire in one hour from the time
     * the response was generated. If omitted, the authorization server SHOULD provide the expiration
     * time via other means or document the default value.
     */
    public GrantedOAuth2AccessTokenBuilder expiresIn(Duration expiresIn) {
        this.expiresIn = requireNonNull(expiresIn, "expiresIn");
        return this;
    }

    /**
     * An {@link Instant} indicating when the Access Token was issued,
     * OPTIONAL. The value is NOT supplied with the Access Token response and calculated approximately using
     * {@value OAuth2Constants#EXPIRES_IN} field.
     */
    public GrantedOAuth2AccessTokenBuilder issuedAt(Instant issuedAt) {
        this.issuedAt = requireNonNull(issuedAt, "issuedAt");
        return this;
    }

    /**
     * {@value OAuth2Constants#REFRESH_TOKEN} Access Token response field,
     * OPTIONAL. The refresh token, which can be used to obtain new access tokens using the same
     * authorization grant as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-6">[RFC6749], Section 6</a>.
     */
    public GrantedOAuth2AccessTokenBuilder refreshToken(String refreshToken) {
        this.refreshToken = requireNonNull(refreshToken, "refreshToken");
        return this;
    }

    /**
     * {@value OAuth2Constants#SCOPE} Access Token Response field,
     * OPTIONAL. An {@link Iterable} of individual scope values.
     */
    public GrantedOAuth2AccessTokenBuilder scope(Iterable<String> scope) {
        this.scope.addAll(requireNonNull(scope, "scope"));
        return this;
    }

    /**
     * {@value OAuth2Constants#SCOPE} Access Token Response field,
     * OPTIONAL. An array of individual scope values.
     */
    public GrantedOAuth2AccessTokenBuilder scope(String... scope) {
        this.scope.add(requireNonNull(scope, "scope"));
        return this;
    }

    /**
     * A pair of extra system-specific token parameters included with Access Token Response,
     * OPTIONAL.
     */
    public GrantedOAuth2AccessTokenBuilder extras(String key, String value) {
        extras.put(key, value);
        return this;
    }

    /**
     * A {@link Map} of extra system-specific token parameters included with Access Token Response,
     * OPTIONAL.
     */
    public GrantedOAuth2AccessTokenBuilder extras(Map<String, String> extras) {
        this.extras.putAll(extras);
        return this;
    }

    /**
     * An {@link Iterable} of extra system-specific token parameters included with Access Token Response,
     * OPTIONAL.
     */
    @SuppressWarnings("UnstableApiUsage")
    public GrantedOAuth2AccessTokenBuilder extras(
            Iterable<? extends Map.Entry<String, String>> extras) {
        this.extras.putAll(extras);
        return this;
    }

    private GrantedOAuth2AccessTokenBuilder rawResponse(String rawResponse) {
        this.rawResponse = requireNonNull(rawResponse, "rawResponse");
        return this;
    }

    /**
     * Builds a new instance of {@link GrantedOAuth2AccessToken} based on the configured parameters.
     */
    public GrantedOAuth2AccessToken build() {
        return new GrantedOAuth2AccessToken(accessToken, tokenType,
                                      (issuedAt == null) ? Instant.now() : issuedAt, expiresIn,
                                            refreshToken, scope.build(), extras.build(), rawResponse);
    }
}

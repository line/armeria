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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ACCESS_TOKEN;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.DEFAULT_TOKEN_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.EXPIRES_IN;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.REFRESH_TOKEN;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.SCOPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.TOKEN_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.ResponseParserUtil.JSON;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.auth.oauth2.CaseUtil;
import com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants;

/**
 * Defines a structure of the Access Token Response, as per
 * <a href="https://tools.ietf.org/html/rfc6749#section-5.1">[RFC6749], Section 5.1</a>.
 */
@UnstableApi
public class GrantedOAuth2AccessToken implements Serializable {

    private static final long serialVersionUID = 8698118404098897958L;

    /**
     * Creates a new {@link GrantedOAuth2AccessToken} based on the {@code JSON}-formatted raw response body and
     * optional raw formatted {@code scope} used to request the token.
     * @param rawResponse {@code JSON}-formatted raw response body.
     * @param requestScope OPTIONAL. A list of space-delimited, case-sensitive strings.
     *                     The strings are defined by the authorization server.
     *                     The authorization server MAY fully or partially ignore the scope requested by the
     *                     client, based on the authorization server policy or the resource owner's
     *                     instructions. If the issued access token scope is different from the one requested
     *                     by the client, the authorization server MUST include the "scope" response
     *                     parameter to inform the client of the actual scope granted.
     *                     If the client omits the scope parameter when requesting authorization, the
     *                     authorization server MUST either process the request using a pre-defined default
     *                     value or fail the request indicating an invalid scope.
     * @return A new instance of {@link GrantedOAuth2AccessToken}.
     */
    public static GrantedOAuth2AccessToken parse(String rawResponse, @Nullable String requestScope) {
        return GrantedOAuth2AccessTokenBuilder.parse(rawResponse, requestScope);
    }

    /**
     * Creates a new {@link GrantedOAuth2AccessTokenBuilder} to build a new {@link GrantedOAuth2AccessToken} and
     * supplied it with a value of {@code access_token} Access Token response field.
     * @return A new instance of {@link GrantedOAuth2AccessTokenBuilder}.
     */
    public static GrantedOAuth2AccessTokenBuilder builder(String accessToken) {
        return new GrantedOAuth2AccessTokenBuilder(accessToken);
    }

    @VisibleForTesting
    static final String ISSUED_AT = "issued_at";

    @VisibleForTesting
    static final String SCOPE_SEPARATOR = " ";
    @VisibleForTesting
    static final char AUTHORIZATION_SEPARATOR = ' ';

    /**
     * {@value OAuth2Constants#ACCESS_TOKEN} Access Token response field,
     * REQUIRED. The access token issued by the authorization server.
     */
    private final String accessToken;

    /**
     * {@value OAuth2Constants#TOKEN_TYPE} Access Token response field,
     * REQUIRED. The type of the token issued as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-7.1">[RFC6749], Section 7.1</a>, e.g. "bearer".
     * Value is case insensitive.
     */
    @Nullable
    private final String tokenType;

    /**
     * {@value OAuth2Constants#EXPIRES_IN} Access Token response field,
     * RECOMMENDED. The lifetime in seconds of the access token. For example, the value "3600" denotes
     * that the access token will expire in one hour from the time the response was generated. If
     * omitted, the authorization server SHOULD provide the expiration time via other means or
     * document the default value.
     */
    @Nullable
    private final Duration expiresIn;

    /**
     * {@value OAuth2Constants#REFRESH_TOKEN} Access Token response field,
     * OPTIONAL. The refresh token, which can be used to obtain new access tokens using the same
     * authorization grant as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-6">[RFC6749], Section 6</a>.
     */
    @Nullable
    private final String refreshToken;

    /**
     * {@value OAuth2Constants#SCOPE} Access Token response field,
     * OPTIONAL, if identical to the scope requested by the client; otherwise, REQUIRED. The scope of
     * the access token as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-3.3">[RFC6749], Section 3.3</a>.
     * A list of space-delimited, case-sensitive scope strings. The strings are defined by the authorization
     * server.
     * The authorization server MAY fully or partially ignore the scope requested by the client, based
     * on the authorization server policy or the resource owner's instructions.  If the issued access
     * token scope is different from the one requested by the client, the authorization server MUST
     * include the "scope" response parameter to inform the client of the actual scope granted.
     * If the client omits the scope parameter when requesting authorization, the authorization server
     * MUST either process the request using a pre-defined default value or fail the request
     * indicating an invalid scope.
     */
    @Nullable
    private final String scope;

    /**
     * A {@link Set} of case-sensitive scope strings. The strings are defined by the authorization
     * server.
     */
    private final Set<String> scopeSet;

    /**
     * A {@link Map} of extra system-specific token parameters included with Access Token response,
     * OPTIONAL.
     */
    private final Map<String, String> extras;

    private final Instant issuedAt;

    @Nullable
    private String rawResponse;

    @Nullable
    private transient Instant expiresAt;

    @Nullable
    private transient String authorization;

    @Nullable
    private transient String toString;

    GrantedOAuth2AccessToken(String accessToken, @Nullable String tokenType,
                             Instant issuedAt, @Nullable Duration expiresIn,
                             @Nullable String refreshToken, @Nullable List<String> scopeList,
                             @Nullable ImmutableMap<String, String> extras,
                             @Nullable String rawResponse) {
        // token fields
        this.accessToken = requireNonNull(accessToken, ACCESS_TOKEN);
        this.tokenType = tokenType;
        this.expiresIn = expiresIn;
        this.issuedAt = requireNonNull(issuedAt, ISSUED_AT);
        this.refreshToken = refreshToken;
        scope = toScopeString(scopeList);
        scopeSet = (scopeList == null) ? ImmutableSet.of() : ImmutableSet.copyOf(scopeList);
        this.extras = (extras == null) ? ImmutableMap.of() : extras;
        // raw response
        this.rawResponse = rawResponse;
    }

    /**
     * {@value OAuth2Constants#ACCESS_TOKEN} Access Token response field,
     * REQUIRED. The access token issued by the authorization server.
     */
    public String accessToken() {
        return accessToken;
    }

    /**
     * {@value OAuth2Constants#TOKEN_TYPE}  Access Token response field,
     * REQUIRED. The type of the token issued as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-7.1">[RFC6749], Section 7.1</a>.
     * Value is case insensitive.
     */
    @Nullable
    public String tokenType() {
        return tokenType;
    }

    /**
     * {@value OAuth2Constants#EXPIRES_IN} Access Token response field,
     * RECOMMENDED. {@link Duration} indicating the lifetime of the access token. For example,
     * the value 3600 seconds denotes that the access token will expire in one hour from the time
     * the response was generated. If omitted, the authorization server SHOULD provide the expiration
     * time via other means or document the default value.
     */
    @Nullable
    public Duration expiresIn() {
        return expiresIn;
    }

    /**
     * An {@link Instant} indicating when the Access Token was issued.
     * The value is NOT supplied with the Access Token response and calculated approximately using
     * {@value OAuth2Constants#EXPIRES_IN} field.
     */
    public Instant issuedAt() {
        return issuedAt;
    }

    /**
     * An {@link Instant} representing a derived value using {@code issuedAt() + expiresIn()}.
     */
    @Nullable
    public Instant expiresAt() {
        if (expiresIn == null) {
            return null;
        }
        if (expiresAt == null) {
            expiresAt = issuedAt.plus(expiresIn);
        }
        return expiresAt;
    }

    /**
     * Indicates whether or not the Access Token expire at the given {@link Instant} time based on
     * {@link #expiresAt()} function.
     */
    public boolean isValid(Instant instant) {
        final Instant expires = expiresAt();
        return (expires == null) || requireNonNull(instant, "instant").isBefore(expires);
    }

    /**
     * Indicates whether or not the Access Token already expired based on {@link #expiresAt()} function.
     */
    public boolean isValid() {
        return isValid(Instant.now());
    }

    /**
     * {@value OAuth2Constants#REFRESH_TOKEN} Access Token response field,
     * OPTIONAL. The refresh token, which can be used to obtain new access tokens using the same
     * authorization grant as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-6">[RFC6749], Section 6</a>.
     */
    @Nullable
    public String refreshToken() {
        return refreshToken;
    }

    /**
     * {@value OAuth2Constants#SCOPE} Access Token response field,
     * OPTIONAL, if identical to the scope requested by the client; otherwise, REQUIRED. The scope of
     * the access token as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-3.3">[RFC6749], Section 3.3</a>.
     * A list of space-delimited, case-sensitive scope strings. The strings are defined by the authorization
     * server.
     * The authorization server MAY fully or partially ignore the scope requested by the client, based
     * on the authorization server policy or the resource owner's instructions.  If the issued access
     * token scope is different from the one requested by the client, the authorization server MUST
     * include the "scope" response parameter to inform the client of the actual scope granted.
     * If the client omits the scope parameter when requesting authorization, the authorization server
     * MUST either process the request using a pre-defined default value or fail the request
     * indicating an invalid scope.
     */
    @Nullable
    public String scope() {
        return scope;
    }

    /**
     * {@value OAuth2Constants#SCOPE} Access Token response field,
     * OPTIONAL, if identical to the scope requested by the client; otherwise, REQUIRED. The scope of
     * the access token as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-3.3">[RFC6749], Section 3.3</a>.
     * A {@link Set} of case-sensitive scope strings. The strings are defined by the authorization
     * server.
     * The authorization server MAY fully or partially ignore the scope requested by the client, based
     * on the authorization server policy or the resource owner's instructions.  If the issued access
     * token scope is different from the one requested by the client, the authorization server MUST
     * include the "scope" response parameter to inform the client of the actual scope granted.
     * If the client omits the scope parameter when requesting authorization, the authorization server
     * MUST either process the request using a pre-defined default value or fail the request
     * indicating an invalid scope.
     */
    public Set<String> scopeSet() {
        return scopeSet;
    }

    /**
     * A {@link Map} of extra system-specific token parameters included with Access Token response,
     * OPTIONAL.
     */
    public Map<String, String> extras() {
        return extras;
    }

    /**
     * A value of the {@link HttpHeaderNames#AUTHORIZATION} header based on this access token.
     */
    public String authorization() {
        if (authorization == null) {
            final String type = (tokenType == null) ? DEFAULT_TOKEN_TYPE : tokenType;
            authorization = CaseUtil.firstUpperAllLowerCase(type) + AUTHORIZATION_SEPARATOR + accessToken;
        }
        return authorization;
    }

    /**
     * {@code JSON}-formatted raw Token Introspection Response body. If the {@link GrantedOAuth2AccessToken} was
     * not parsed out of the raw response body, this value calculated based on the other
     * {@link GrantedOAuth2AccessToken} values.
     */
    public String rawResponse() {
        if (rawResponse == null) {
            // WARNING: do not include {@code issuedAt()} to the raw response
            // as {@code issuedAt()} is a derived field and it's not part of the OAuth2 server response
            rawResponse = composeRawResponse(accessToken, tokenType,
                                             null, expiresIn, refreshToken, scope, extras);
        }
        return rawResponse;
    }

    @Override
    public String toString() {
        if (toString == null) {
            // include {@code issuedAt()} to toString()
            toString = composeRawResponse(accessToken, tokenType, issuedAt, expiresIn,
                                          refreshToken, scope, extras);
        }
        return toString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GrantedOAuth2AccessToken)) {
            return false;
        }
        final GrantedOAuth2AccessToken that = (GrantedOAuth2AccessToken) o;
        return Objects.equals(rawResponse(), that.rawResponse());
    }

    @Override
    public int hashCode() {
        return rawResponse().hashCode();
    }

    @Nullable
    private static String toScopeString(@Nullable List<String> scopeList) {
        if (scopeList == null || scopeList.isEmpty()) {
            return null;
        }
        return String.join(SCOPE_SEPARATOR, scopeList);
    }

    private static String composeRawResponse(
            String accessToken, @Nullable String tokenType,
            @Nullable Instant issuedAt, @Nullable Duration expiresIn,
            @Nullable String refreshToken, @Nullable String scope, Map<String, String> extras) {

        final ObjectNode node = JSON.createObjectNode();
        node.put(ACCESS_TOKEN, accessToken);
        if (tokenType != null) {
            node.put(TOKEN_TYPE, tokenType);
        }
        if (issuedAt != null) {
            node.put(ISSUED_AT, ISO_INSTANT.format(issuedAt));
        }
        if (expiresIn != null) {
            node.put(EXPIRES_IN, expiresIn.getSeconds());
        }
        if (refreshToken != null) {
            node.put(REFRESH_TOKEN, refreshToken);
        }
        if (scope != null) {
            node.put(SCOPE, scope);
        }
        extras.forEach(node::put);

        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}

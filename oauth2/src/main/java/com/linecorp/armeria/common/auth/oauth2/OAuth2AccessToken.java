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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaderNames;

/**
 * Defines a structure of the Access Token Response, as per
 * <a href="https://tools.ietf.org/html/rfc6749#section-5.1">[RFC6749], Section 5.1</a>.
 */
public class OAuth2AccessToken implements Serializable {

    private static final long serialVersionUID = 8698118404098897958L;

    /**
     * Creates a new {@link OAuth2AccessToken} based on the {@code JSON}-formatted raw response body and
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
     * @return A new instance of {@link OAuth2AccessToken}.
     */
    public static OAuth2AccessToken of(String rawResponse, @Nullable String requestScope) {
        return OAuth2AccessTokenBuilder.of(rawResponse, requestScope);
    }

    /**
     * Creates a new {@link OAuth2AccessTokenBuilder} to build a new {@link OAuth2AccessToken} and
     * supplied it with a value of {@code access_token} Access Token response field.
     * @return A new instance of {@link OAuth2AccessTokenBuilder}.
     */
    public static OAuth2AccessTokenBuilder builder(String accessToken) {
        return new OAuth2AccessTokenBuilder(accessToken);
    }

    public static final String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String SCOPE = "scope";
    public static final String TOKEN_TYPE = "token_type";
    public static final String EXPIRES_IN = "expires_in";

    static final String ISSUED_AT = "issued_at";

    static final String SCOPE_SEPARATOR = " ";
    static final char AUTHORIZATION_SEPARATOR = ' ';

    static final String DEFAULT_TOKEN_TYPE = "bearer";

    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@code access_token} Access Token response field,
     * REQUIRED. The access token issued by the authorization server.
     */
    private final String accessToken;

    /**
     * {@code token_type} Access Token response field,
     * REQUIRED. The type of the token issued as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-7.1">[RFC6749], Section 7.1</a>, e.g. "bearer".
     * Value is case insensitive.
     */
    @Nullable
    private final String tokenType;

    /**
     * {@code expires_in} Access Token response field,
     * RECOMMENDED. The lifetime in seconds of the access token. For example, the value "3600" denotes
     * that the access token will expire in one hour from the time the response was generated. If
     * omitted, the authorization server SHOULD provide the expiration time via other means or
     * document the default value.
     */
    @Nullable
    private final Duration expiresIn;

    /**
     * {@code refresh_token} Access Token response field,
     * OPTIONAL. The refresh token, which can be used to obtain new access tokens using the same
     * authorization grant as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-6">[RFC6749], Section 6</a>.
     */
    @Nullable
    private final String refreshToken;

    /**
     * {@code scope} Access Token response field,
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

    OAuth2AccessToken(String accessToken, @Nullable String tokenType,
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
     * {@code access_token} Access Token response field,
     * REQUIRED. The access token issued by the authorization server.
     */
    public String accessToken() {
        return accessToken;
    }

    /**
     * {@code token_type}  Access Token response field,
     * REQUIRED. The type of the token issued as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-7.1">[RFC6749], Section 7.1</a>.
     * Value is case insensitive.
     */
    @Nullable
    public String tokenType() {
        return tokenType;
    }

    /**
     * {@code expires_in} Access Token response field,
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
     * {@code expires_in} field.
     */
    public Instant issuedAt() {
        return issuedAt;
    }

    /**
     * An {@link Instant} representing a derived value using {@code issuedAt + expiresIn}.
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
     * {@code refresh_token} Access Token response field,
     * OPTIONAL. The refresh token, which can be used to obtain new access tokens using the same
     * authorization grant as described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-6">[RFC6749], Section 6</a>.
     */
    @Nullable
    public String refreshToken() {
        return refreshToken;
    }

    /**
     * {@code scope} Access Token response field,
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
     * {@code scope} Access Token response field,
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
     * {@code JSON}-formatted raw Token Introspection Response body. If the {@link OAuth2AccessToken} was not
     * parsed out of the raw response body, this value calculated based on the other {@link OAuth2AccessToken}
     * values.
     */
    public String rawResponse() {
        if (rawResponse == null) {
            // WARNING: do not include {@code issuedAt} to the raw response
            // as {@code issuedAt} is a derived field and it's not part of the OAuth2 server response
            rawResponse = composeRawResponse(accessToken, tokenType,
                                             null, expiresIn, refreshToken, scope, extras);
        }
        return rawResponse;
    }

    @Override
    public String toString() {
        if (toString == null) {
            // include {@code issuedAt} to toString()
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
        if (!(o instanceof OAuth2AccessToken)) {
            return false;
        }
        final OAuth2AccessToken that = (OAuth2AccessToken) o;
        return Objects.equals(rawResponse(), that.rawResponse());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(rawResponse());
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

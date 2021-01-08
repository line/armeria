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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ACTIVE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.AUDIENCE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.CLIENT_ID;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.EXPIRES_AT;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ISSUED_AT;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ISSUER;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.JWT_ID;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.NOT_BEFORE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.SCOPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.SUBJECT;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.TOKEN_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.USER_NAME;
import static com.linecorp.armeria.internal.common.auth.oauth2.ResponseParserUtil.JSON;
import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants;

/**
 * Defines a structure of the Token Introspection Response, as per
 * <a href="https://tools.ietf.org/html/rfc7662#section-2.2">[RFC7662], Section 2.2</a>.
 */
@UnstableApi
public class OAuth2TokenDescriptor implements Serializable {

    private static final long serialVersionUID = -3976877781134216467L;

    /**
     * Creates a new {@link OAuth2TokenDescriptor} based on the {@code JSON}-formatted raw response body.
     * @param rawResponse {@code JSON}-formatted raw response body.
     * @return A new instance of {@link OAuth2TokenDescriptor}.
     */
    public static OAuth2TokenDescriptor parse(String rawResponse) {
        return OAuth2TokenDescriptorBuilder.parse(rawResponse);
    }

    /**
     * Creates a new {@link OAuth2TokenDescriptorBuilder} to build a new {@link OAuth2TokenDescriptor} and
     * supplied it with a value of {@code active} Token Introspection Response field.
     * @return A new instance of {@link OAuth2TokenDescriptorBuilder}.
     */
    public static OAuth2TokenDescriptorBuilder builder(boolean active) {
        return new OAuth2TokenDescriptorBuilder(active);
    }

    static final String SCOPE_SEPARATOR = " ";

    /**
     * {@value OAuth2Constants#ACTIVE} Token Introspection Response field,
     * REQUIRED. Boolean indicator of whether or not the presented token is currently active. The
     * specifics of a token's "active" state will vary depending on the implementation of the
     * authorization server and the information it keeps about its tokens, but a "true" value return
     * for the "active" property will generally indicate that a given token has been issued by this
     * authorization server, has not been revoked by the resource owner, and is within its given time
     * window of validity (e.g., after its issuance time and before its expiration time).
     */
    private final boolean active;

    /**
     * {@value OAuth2Constants#SCOPE} Token Introspection Response field,
     * OPTIONAL. A JSON string containing a space-separated list of scopes associated with this token,
     * in the format described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-3.3">[RFC6749], Section 3.3</a>.
     */
    @Nullable
    private final String scope;

    /**
     * A {@link Set} of case-sensitive scope strings. The strings are defined by the authorization
     * server.
     */
    private final Set<String> scopeSet;

    /**
     * {@value OAuth2Constants#CLIENT_ID} Token Introspection Response field,
     * OPTIONAL. Client identifier for the OAuth 2.0 client that requested this token.
     */
    @Nullable
    private final String clientId;

    /**
     * {@value OAuth2Constants#USER_NAME} Token Introspection Response field,
     * OPTIONAL. Human-readable identifier for the resource owner who authorized this token.
     */
    @Nullable
    private final String userName;

    /**
     * {@value OAuth2Constants#TOKEN_TYPE} Token Introspection Response field,
     * OPTIONAL. Type of the token as defined at
     * <a href="http://tools.ietf.org/html/rfc6749#section-7.1">[RFC6749], Section 7.1</a>.
     */
    @Nullable
    private final String tokenType;

    /**
     * {@value OAuth2Constants#EXPIRES_AT} Token Introspection Response field,
     * OPTIONAL. Integer timestamp, measured in the number of seconds since January 1 1970 UTC,
     * indicating when this token will expire, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    private final Instant expiresAt;

    /**
     * {@value OAuth2Constants#ISSUED_AT} Token Introspection Response field,
     * OPTIONAL. Integer timestamp, measured in the number of seconds since January 1 1970 UTC,
     * indicating when this token was originally issued, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    private final Instant issuedAt;

    /**
     * {@value OAuth2Constants#NOT_BEFORE} Token Introspection Response field,
     * OPTIONAL. Integer timestamp, measured in the number of seconds since January 1 1970 UTC,
     * indicating when this token is not to be used before, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    private final Instant notBefore;

    /**
     * {@value OAuth2Constants#SUBJECT} Token Introspection Response field,
     * OPTIONAL. Subject of the token. Usually a machine-readable
     * identifier of the resource owner who authorized this token. As defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    private final String subject;

    /**
     * {@value OAuth2Constants#AUDIENCE} Token Introspection Response field,
     * OPTIONAL. Service-specific string identifier or list of string identifiers representing the
     * intended audience for this token, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    private final String audience;

    /**
     * {@value OAuth2Constants#ISSUER} Token Introspection Response field,
     * OPTIONAL. String representing the issuer of this token, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    private final String issuer;

    /**
     * {@value OAuth2Constants#JWT_ID} Token Introspection Response field,
     * OPTIONAL. String identifier for the token - JWT ID, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    private final String jwtId;

    /**
     * A {@link Map} of extra system-specific token parameters included with Token Introspection Response,
     * OPTIONAL.
     */
    private final Map<String, String> extras;

    @Nullable
    private String rawResponse;

    @Nullable
    private transient String toString;

    OAuth2TokenDescriptor(boolean active, @Nullable List<String> scopeList, @Nullable String clientId,
                          @Nullable String userName, @Nullable String tokenType,
                          @Nullable Instant expiresAt, @Nullable Instant issuedAt,
                          @Nullable Instant notBefore, @Nullable String subject,
                          @Nullable String audience, @Nullable String issuer,
                          @Nullable String jwtId, @Nullable ImmutableMap<String, String> extras,
                          @Nullable String rawResponse) {
        this.active = active;
        scope = toScopeString(scopeList);
        scopeSet = (scopeList == null) ? ImmutableSet.of() : ImmutableSet.copyOf(scopeList);
        this.clientId = clientId;
        this.userName = userName;
        this.tokenType = tokenType;
        this.expiresAt = expiresAt;
        this.issuedAt = issuedAt;
        this.notBefore = notBefore;
        this.subject = subject;
        this.audience = audience;
        this.issuer = issuer;
        this.jwtId = jwtId;
        this.extras = (extras == null) ? ImmutableMap.of() : extras;
        // raw response
        this.rawResponse = rawResponse;
    }

    /**
     * {@value OAuth2Constants#ACTIVE} Token Introspection Response field,
     * REQUIRED. Boolean indicator of whether or not the presented token is currently active. The
     * specifics of a token's "active" state will vary depending on the implementation of the
     * authorization server and the information it keeps about its tokens, but a "true" value return
     * for the "active" property will generally indicate that a given token has been issued by this
     * authorization server, has not been revoked by the resource owner, and is within its given time
     * window of validity (e.g., after its issuance time and before its expiration time).
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Indicates whether or not the Token expire at the given {@link Instant} time based on
     * {@link #expiresAt()} function.
     */
    public boolean isValid(Instant instant) {
        final Instant expires = expiresAt();
        return (expires == null) || requireNonNull(instant, "instant").isBefore(expires);
    }

    /**
     * Indicates whether or not the Token already expired based on {@link #expiresAt()} function.
     */
    public boolean isValid() {
        return isValid(Instant.now());
    }

    /**
     * Indicates whether or not the Token used prematurely based on {@link #notBefore()} function.
     */
    public boolean isNotBefore() {
        final Instant notBefore = notBefore();
        return (notBefore == null) || Instant.now().isAfter(notBefore);
    }

    /**
     * {@value OAuth2Constants#SCOPE} Token Introspection Response field,
     * OPTIONAL. A JSON string containing a space-separated list of scopes associated with this token,
     * in the format described at
     * <a href="http://tools.ietf.org/html/rfc6749#section-3.3">[RFC6749], Section 3.3</a>.
     */
    @Nullable
    public String scope() {
        return scope;
    }

    /**
     * {@value OAuth2Constants#SCOPE} Token Introspection Response field,
     * OPTIONAL. A {@link Set} of case-sensitive scope strings. The strings are defined by the authorization
     * server.
     */
    public Set<String> scopeSet() {
        return scopeSet;
    }

    /**
     * {@value OAuth2Constants#CLIENT_ID} Token Introspection Response field,
     * OPTIONAL. Client identifier for the OAuth 2.0 client that requested this token.
     */
    @Nullable
    public String clientId() {
        return clientId;
    }

    /**
     * {@value OAuth2Constants#USER_NAME} Token Introspection Response field,
     * OPTIONAL. Human-readable identifier for the resource owner who authorized this token.
     */
    @Nullable
    public String userName() {
        return userName;
    }

    /**
     * {@value OAuth2Constants#TOKEN_TYPE} Token Introspection Response field,
     * OPTIONAL. Type of the token as defined at
     * <a href="http://tools.ietf.org/html/rfc6749#section-7.1">[RFC6749], Section 7.1</a>.
     */
    @Nullable
    public String tokenType() {
        return tokenType;
    }

    /**
     * {@value OAuth2Constants#EXPIRES_AT} Token Introspection Response field,
     * OPTIONAL. {@link Instant} timestamp, indicating when this token will expire, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * {@value OAuth2Constants#ISSUED_AT} Token Introspection Response field,
     * OPTIONAL. {@link Instant} timestamp, indicating when this token was originally issued, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    public Instant issuedAt() {
        return issuedAt;
    }

    /**
     * {@link Duration} indicating the lifetime of the access token. The value is NOT supplied with the
     * Token Introspection response and calculated based on {@value OAuth2Constants#ISSUED_AT} and
     * {@value OAuth2Constants#EXPIRES_AT} response fields every time this method invoked.
     */
    @Nullable
    public Duration expiresIn() {
        if (issuedAt != null && expiresAt != null) {
            return Duration.ofMillis(issuedAt.until(expiresAt, ChronoUnit.MILLIS));
        }
        return null;
    }

    /**
     * {@value OAuth2Constants#NOT_BEFORE} Token Introspection Response field,
     * OPTIONAL. {@link Instant} timestamp, indicating when this token is not to be used before, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    public Instant notBefore() {
        return notBefore;
    }

    /**
     * {@value OAuth2Constants#SUBJECT} Token Introspection Response field,
     * OPTIONAL. Subject of the token. Usually a machine-readable
     * identifier of the resource owner who authorized this token. As defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    public String subject() {
        return subject;
    }

    /**
     * {@value OAuth2Constants#AUDIENCE} Token Introspection Response field,
     * OPTIONAL. Service-specific string identifier or list of string identifiers representing the
     * intended audience for this token, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    public String audience() {
        return audience;
    }

    /**
     * {@value OAuth2Constants#ISSUER} Token Introspection Response field,
     * OPTIONAL. String representing the issuer of this token, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    public String issuer() {
        return issuer;
    }

    /**
     * {@value OAuth2Constants#JWT_ID} Token Introspection Response field,
     * OPTIONAL. String identifier for the token - JWT ID, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    @Nullable
    public String jwtId() {
        return jwtId;
    }

    /**
     * A {@link Map} of extra system-specific token parameters included with Token Introspection Response,
     * OPTIONAL.
     */
    public Map<String, String> extras() {
        return extras;
    }

    /**
     * {@code JSON}-formatted raw Token Introspection Response body. If the {@link OAuth2TokenDescriptor} was
     * not parsed out of the raw response body, this value calculated based on the other
     * {@link OAuth2TokenDescriptor} values.
     */
    public String rawResponse() {
        if (rawResponse == null) {
            rawResponse = composeRawResponse(active, scope, clientId, userName, tokenType,
                                             expiresAt, issuedAt, notBefore,
                                             subject, audience, issuer,
                                             jwtId, extras);
        }
        return rawResponse;
    }

    @Override
    public String toString() {
        if (toString == null) {
            toString = rawResponse();
        }
        return toString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OAuth2TokenDescriptor)) {
            return false;
        }
        final OAuth2TokenDescriptor that = (OAuth2TokenDescriptor) o;
        return Objects.equals(rawResponse(), that.rawResponse());
    }

    @Override
    public int hashCode() {
        return rawResponse().hashCode();
    }

    /**
     * Composes space-separated list of scopes as a {@code JSON} string.
     */
    @Nullable
    private static String toScopeString(@Nullable List<String> scopeList) {
        if (scopeList == null || scopeList.isEmpty()) {
            return null;
        }
        return String.join(SCOPE_SEPARATOR, scopeList);
    }

    /**
     * Composes {@code JSON}-formatted raw Token Introspection Response body based on the other
     * {@link OAuth2TokenDescriptor} values.
     */
    private static String composeRawResponse(
            boolean active, @Nullable String scope, @Nullable String clientId,
            @Nullable String userName, @Nullable String tokenType,
            @Nullable Instant expiresAt, @Nullable Instant issuedAt,
            @Nullable Instant notBefore, @Nullable String subject,
            @Nullable String audience, @Nullable String issuer,
            @Nullable String jwtId, Map<String, String> extras) {

        final ObjectNode node = JSON.createObjectNode();
        node.put(ACTIVE, active);
        if (scope != null) {
            node.put(SCOPE, scope);
        }
        if (clientId != null) {
            node.put(CLIENT_ID, clientId);
        }
        if (userName != null) {
            node.put(USER_NAME, userName);
        }
        if (tokenType != null) {
            node.put(TOKEN_TYPE, tokenType);
        }
        if (expiresAt != null) {
            node.put(EXPIRES_AT, expiresAt.getEpochSecond());
        }
        if (issuedAt != null) {
            node.put(ISSUED_AT, issuedAt.getEpochSecond());
        }
        if (notBefore != null) {
            node.put(NOT_BEFORE, notBefore.getEpochSecond());
        }
        if (subject != null) {
            node.put(SUBJECT, subject);
        }
        if (audience != null) {
            node.put(AUDIENCE, audience);
        }
        if (issuer != null) {
            node.put(ISSUER, issuer);
        }
        if (jwtId != null) {
            node.put(JWT_ID, jwtId);
        }
        extras.forEach(node::put);

        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}

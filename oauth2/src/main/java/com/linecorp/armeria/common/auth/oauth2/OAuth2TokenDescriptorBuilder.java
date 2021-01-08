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

import static com.linecorp.armeria.common.auth.oauth2.OAuth2TokenDescriptor.SCOPE_SEPARATOR;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds an instance of {@link OAuth2TokenDescriptor}.
 */
@UnstableApi
public final class OAuth2TokenDescriptorBuilder {

    private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE =
            new TypeReference<LinkedHashMap<String, String>>() {};

    static OAuth2TokenDescriptor parse(String rawResponse) {

        final LinkedHashMap<String, String> map;
        try {
            map = JSON.readValue(requireNonNull(rawResponse, "rawResponse"), MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        final OAuth2TokenDescriptorBuilder builder = new OAuth2TokenDescriptorBuilder(
                Boolean.parseBoolean(requireNonNull(map.remove(ACTIVE), ACTIVE)));

        final String scope = map.remove(SCOPE);
        if (scope != null) {
            builder.scope(scope.split(SCOPE_SEPARATOR));
        }
        final String clientId = map.remove(CLIENT_ID);
        if (clientId != null) {
            builder.clientId(clientId);
        }
        final String userName = map.remove(USER_NAME);
        if (userName != null) {
            builder.userName(userName);
        }
        final String tokenType = map.remove(TOKEN_TYPE);
        if (tokenType != null) {
            builder.tokenType(tokenType);
        }
        final String expiresAt = map.remove(EXPIRES_AT);
        if (expiresAt != null) {
            builder.expiresAt(Instant.ofEpochSecond(Long.parseLong(expiresAt)));
        }
        final String issuedAt = map.remove(ISSUED_AT);
        if (issuedAt != null) {
            builder.issuedAt(Instant.ofEpochSecond(Long.parseLong(issuedAt)));
        }
        final String notBefore = map.remove(NOT_BEFORE);
        if (notBefore != null) {
            builder.notBefore(Instant.ofEpochSecond(Long.parseLong(notBefore)));
        }
        final String subject = map.remove(SUBJECT);
        if (subject != null) {
            builder.subject(subject);
        }
        final String audience = map.remove(AUDIENCE);
        if (audience != null) {
            builder.audience(audience);
        }
        final String issuer = map.remove(ISSUER);
        if (issuer != null) {
            builder.issuer(issuer);
        }
        final String jwtId = map.remove(JWT_ID);
        if (jwtId != null) {
            builder.jwtId(jwtId);
        }
        builder.extras(map);

        builder.rawResponse(rawResponse);

        return builder.build();
    }

    private final boolean active;

    private final ImmutableList.Builder<String> scope = ImmutableList.builder();

    @Nullable
    private String clientId;

    @Nullable
    private String userName;

    @Nullable
    private String tokenType;

    @Nullable
    private Instant expiresAt;

    @Nullable
    private Instant issuedAt;

    @Nullable
    private Instant notBefore;

    @Nullable
    private String subject;

    @Nullable
    private String audience;

    @Nullable
    private String issuer;

    @Nullable
    private String jwtId;

    @Nullable
    private String rawResponse;

    private final ImmutableMap.Builder<String, String> extras = ImmutableMap.builder();

    /**
     * Constructs a new instance of {@link OAuth2TokenDescriptorBuilder} given the mandatory token
     * {@code active} status.
     * @param active {@code active} Token Introspection Response field,
     *               REQUIRED. Boolean indicator of whether or not the presented token is currently active. The
     *               specifics of a token's "active" state will vary depending on the implementation of the
     *               authorization server and the information it keeps about its tokens, but a "true" value
     *               return for the "active" property will generally indicate that a given token has been issued
     *               by this authorization server, has not been revoked by the resource owner, and is within its
     *               given time window of validity (e.g., after its issuance time and before its expiration
     *               time).
     */
    OAuth2TokenDescriptorBuilder(boolean active) {
        this.active = active;
    }

    /**
     * {@code scope} Token Introspection Response field,
     * OPTIONAL. An {@link Iterable} of individual scope values.
     */
    public OAuth2TokenDescriptorBuilder scope(Iterable<String> scope) {
        this.scope.addAll(requireNonNull(scope, "scope"));
        return this;
    }

    /**
     * {@code scope} Token Introspection Response field,
     * OPTIONAL. An array of individual scope values.
     */
    public OAuth2TokenDescriptorBuilder scope(String... scope) {
        this.scope.add(requireNonNull(scope, "scope"));
        return this;
    }

    /**
     * {@code client_id} Token Introspection Response field,
     * OPTIONAL. Client identifier for the OAuth 2.0 client that requested this token.
     */
    public OAuth2TokenDescriptorBuilder clientId(String clientId) {
        this.clientId = requireNonNull(clientId, "clientId");
        return this;
    }

    /**
     * {@code username} Token Introspection Response field,
     * OPTIONAL. Human-readable identifier for the resource owner who authorized this token.
     */
    public OAuth2TokenDescriptorBuilder userName(String userName) {
        this.userName = requireNonNull(userName, "userName");
        return this;
    }

    /**
     * {@code token_type} Token Introspection Response field,
     * OPTIONAL. Type of the token as defined at
     * <a href="http://tools.ietf.org/html/rfc6749#section-7.1">[RFC6749], Section 7.1</a>.
     */
    public OAuth2TokenDescriptorBuilder tokenType(String tokenType) {
        this.tokenType = requireNonNull(tokenType, "tokenType");
        return this;
    }

    /**
     * {@code exp} Token Introspection Response field,
     * OPTIONAL. {@link Instant} timestamp, indicating when this token will expire, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    public OAuth2TokenDescriptorBuilder expiresAt(Instant expiresAt) {
        this.expiresAt = requireNonNull(expiresAt, "expiresAt");
        return this;
    }

    /**
     * {@code iat} Token Introspection Response field,
     * OPTIONAL. {@link Instant} timestamp, indicating when this token was originally issued, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    public OAuth2TokenDescriptorBuilder issuedAt(Instant issuedAt) {
        this.issuedAt = requireNonNull(issuedAt, "issuedAt");
        return this;
    }

    /**
     * {@code nbf} Token Introspection Response field,
     * OPTIONAL. {@link Instant} timestamp, indicating when this token is not to be used before, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    public OAuth2TokenDescriptorBuilder notBefore(Instant notBefore) {
        this.notBefore = requireNonNull(notBefore, "notBefore");
        return this;
    }

    /**
     * {@code sub} Token Introspection Response field,
     * OPTIONAL. Subject of the token. Usually a machine-readable
     * identifier of the resource owner who authorized this token. As defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    public OAuth2TokenDescriptorBuilder subject(String subject) {
        this.subject = requireNonNull(subject, "subject");
        return this;
    }

    /**
     * {@code aud} Token Introspection Response field,
     * OPTIONAL. Service-specific string identifier or list of string identifiers representing the
     * intended audience for this token, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    public OAuth2TokenDescriptorBuilder audience(String audience) {
        this.audience = requireNonNull(audience, "audience");
        return this;
    }

    /**
     * {@code iss} Token Introspection Response field,
     * OPTIONAL. String representing the issuer of this token, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    public OAuth2TokenDescriptorBuilder issuer(String issuer) {
        this.issuer = requireNonNull(issuer, "issuer");
        return this;
    }

    /**
     * {@code jti} Token Introspection Response field,
     * OPTIONAL. String identifier for the token - JWT ID, as defined at
     * <a href="https://tools.ietf.org/html/rfc7519">[RFC7519]</a>.
     */
    public OAuth2TokenDescriptorBuilder jwtId(String jwtId) {
        this.jwtId = requireNonNull(jwtId, "jwtId");
        return this;
    }

    /**
     * A pair of extra system-specific token parameters included with Token Introspection Response,
     * OPTIONAL.
     */
    public OAuth2TokenDescriptorBuilder extras(String key, String value) {
        extras.put(key, value);
        return this;
    }

    /**
     * A {@link Map} of extra system-specific token parameters included with Token Introspection Response,
     * OPTIONAL.
     */
    public OAuth2TokenDescriptorBuilder extras(Map<String, String> extras) {
        this.extras.putAll(extras);
        return this;
    }

    /**
     * An {@link Iterable} of extra system-specific token parameters included with Token Introspection Response,
     * OPTIONAL.
     */
    @SuppressWarnings("UnstableApiUsage")
    public OAuth2TokenDescriptorBuilder extras(Iterable<? extends Map.Entry<String, String>> extras) {
        this.extras.putAll(extras);
        return this;
    }

    private OAuth2TokenDescriptorBuilder rawResponse(String rawResponse) {
        this.rawResponse = requireNonNull(rawResponse, "rawResponse");
        return this;
    }

    /**
     * Builds a new instance of {@link OAuth2TokenDescriptor} based on the configured parameters.
     */
    public OAuth2TokenDescriptor build() {
        return new OAuth2TokenDescriptor(active, scope.build(), clientId, userName, tokenType,
                                         expiresAt, issuedAt, notBefore, subject, audience, issuer, jwtId,
                                         extras.build(), rawResponse);
    }
}

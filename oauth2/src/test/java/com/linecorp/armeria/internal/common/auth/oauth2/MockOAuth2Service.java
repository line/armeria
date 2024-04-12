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

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2AccessToken;

public abstract class MockOAuth2Service {

    public static final String INVALID_REQUEST = "{\"error\":\"invalid_request\"}";
    public static final String INVALID_CLIENT = "{\"error\":\"invalid_client\"}";
    public static final String INVALID_GRANT = "{\"error\":\"invalid_grant\"}";
    public static final String NOT_ACTIVE_RESPONSE = "{\"active\":false}";
    public static final String UNSUPPORTED_GRANT_TYPE = "{\"error\":\"unsupported_grant_type\"}";
    public static final String UNAUTHORIZED_CLIENT = "{\"error\":\"unauthorized_client\"}";

    private static final Pattern CREDENTIALS_PATTERN = Pattern.compile(
            "(?<clientId>.+):(?<clientSecret>.*)");
    private static final Pattern BASIC_AUTHORIZATION_PATTERN = Pattern.compile(
            "\\s*(?i)basic\\s+(?<credential>\\S+)\\s*");
    private static final String WWW_AUTHENTICATE_RESPONSE = "Basic realm=\"%s\"";

    @Nullable
    private static Map.Entry<String, String> decodeClientCredentials(String clientCredential) {
        final String decodedCredential = new String(Base64.getDecoder().decode(clientCredential),
                                                    StandardCharsets.UTF_8);
        final Matcher matcher = CREDENTIALS_PATTERN.matcher(decodedCredential);
        if (!matcher.matches()) {
            // invalid credentials
            return null;
        }
        final String clientId = matcher.group("clientId");
        final String clientSecret = matcher.group("clientSecret");
        return new SimpleImmutableEntry<>(clientId, clientSecret);
    }

    @Nullable
    private static String extractAuthorization(Pattern authorizationPattern,
                                               @Nullable String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isEmpty()) {
            return null;
        }
        final Matcher matcher = authorizationPattern.matcher(authorizationHeader);
        if (!matcher.matches()) {
            // Invalid authorization header
            return null;
        }

        return matcher.group("credential");
    }

    @Nullable
    private static String extractBasicAuthorization(@Nullable String authorizationHeader) {
        return extractAuthorization(BASIC_AUTHORIZATION_PATTERN, authorizationHeader);
    }

    private final Map<String, String> authorizedClients = new HashMap<>(); // clientId -> clientSecret
    private final Map<String, String> clientTokens = new HashMap<>(); // tokenId -> clientId
    private final Map<String, MockOAuth2AccessToken> accessTokens = new HashMap<>(); // tokenId -> tokenObj

    protected Map<String, String> authorizedClients() {
        return authorizedClients;
    }

    protected Map<String, String> clientTokens() {
        return clientTokens;
    }

    protected Map<String, MockOAuth2AccessToken> accessTokens() {
        return accessTokens;
    }

    public MockOAuth2Service withAuthorizedClient(String clientId, String clientSecret) {
        authorizedClients.put(clientId, clientSecret);
        return this;
    }

    public MockOAuth2Service withClientToken(String clientId, MockOAuth2AccessToken token) {
        if (!authorizedClients.containsKey(clientId)) {
            throw new NoSuchElementException(clientId);
        }
        clientTokens.put(token.accessToken(), clientId);
        accessTokens.put(token.accessToken(), token);
        return this;
    }

    public Set<String> findClientTokens(String clientId) {
        final Set<String> tokens = new HashSet<>();
        for (Map.Entry<String, String> clientToken : clientTokens.entrySet()) {
            if (clientId.equals(clientToken.getValue())) {
                tokens.add(clientToken.getKey());
            }
        }
        return tokens;
    }

    @Nullable
    protected MockOAuth2AccessToken findToken(String token) {
        return accessTokens.get(token);
    }

    protected boolean isAuthorizedClient(String clientId, String clientSecret) {
        return clientSecret.equals(authorizedClients.get(clientId));
    }

    protected boolean isAuthorizedClient(@Nullable Map.Entry<String, String> clientCredential) {
        return (clientCredential != null) &&
               isAuthorizedClient(clientCredential.getKey(), clientCredential.getValue());
    }

    protected boolean isAuthorizedClient(@Nullable String clientCredential) {
        return (clientCredential != null) &&
               isAuthorizedClient(decodeClientCredentials(clientCredential));
    }

    protected String extractClientId(@Nullable String authorization) {
        final String clientCredential = extractBasicAuthorization(authorization);
        final Map.Entry<String, String> clientCredentials = decodeClientCredentials(
                requireNonNull(clientCredential, "clientCredential"));
        return requireNonNull(clientCredentials, "clientCredentials").getKey();
    }

    @Nullable
    protected HttpResponse verifyClientCredentials(Optional<String> auth, String realm) {
        if (!auth.isPresent()) {
            return HttpResponse.of(ResponseHeaders.of(HttpStatus.UNAUTHORIZED, HttpHeaderNames.WWW_AUTHENTICATE,
                                                      String.format(WWW_AUTHENTICATE_RESPONSE, realm)));
        }
        final String credential = extractBasicAuthorization(auth.get());
        if (credential == null) {
            return HttpResponse.of(ResponseHeaders.of(HttpStatus.UNAUTHORIZED, HttpHeaderNames.WWW_AUTHENTICATE,
                                                      String.format(WWW_AUTHENTICATE_RESPONSE, realm)));
        }
        if (!isAuthorizedClient(credential)) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, INVALID_CLIENT);
        }
        return null;
    }
}

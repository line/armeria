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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.linecorp.armeria.common.annotation.Nullable;

public class MockOAuth2AccessToken {

    public static final OAuth2TokenDescriptor INACTIVE_TOKEN =
            OAuth2TokenDescriptor.parse("{\"active\":false}");

    private final String accessToken;
    private final GrantedOAuth2AccessToken grantedToken;
    private final OAuth2TokenDescriptor tokenDescriptor;

    @SuppressWarnings("DynamicRegexReplaceableByCompiledPattern")
    public static MockOAuth2AccessToken generate(String clientId, @Nullable String username,
                                                 Duration expiresIn, @Nullable Map<String, String> extras,
                                                 @Nullable String... scope) {

        final Instant issuedAt = Instant.now();
        final OAuth2TokenDescriptorBuilder builder = OAuth2TokenDescriptor.builder(true).clientId(clientId)
                                                                          .tokenType("bearer")
                                                                          .expiresAt(issuedAt.plus(expiresIn))
                                                                          .issuedAt(issuedAt)
                                                                          .subject("access_token")
                                                                          .issuer("MockOAuth2Server");
        if (username != null) {
            builder.userName(username);
        }
        if (extras != null) {
            builder.extras(extras);
        }
        if (scope != null) {
            builder.scope(scope);
        }
        final OAuth2TokenDescriptor descriptor = builder.build();
        final String accessToken = UUID.randomUUID().toString().replaceAll("-", "");
        final String refreshToken = UUID.randomUUID().toString().replaceAll("-", "");
        return new MockOAuth2AccessToken(accessToken, descriptor, refreshToken);
    }

    public MockOAuth2AccessToken(String accessToken, OAuth2TokenDescriptor descriptor,
                                 @Nullable String refreshToken) {
        this.accessToken = accessToken;
        tokenDescriptor = descriptor;
        final GrantedOAuth2AccessTokenBuilder grantedTokenBuilder =
                new GrantedOAuth2AccessTokenBuilder(requireNonNull(accessToken, "accessToken"))
                        .scope(requireNonNull(descriptor, "descriptor").scope())
                        .issuedAt(requireNonNull(descriptor.issuedAt(), "issuedAt"));
        @Nullable
        final String tokenType = descriptor.tokenType();
        if (tokenType != null) {
            grantedTokenBuilder.tokenType(tokenType);
        }
        @Nullable
        final Duration expiresIn = descriptor.expiresIn();
        if (expiresIn != null) {
            grantedTokenBuilder.expiresIn(expiresIn);
        }
        if (refreshToken != null) {
            grantedTokenBuilder.refreshToken(refreshToken);
        }
        grantedToken = grantedTokenBuilder.build();
    }

    public String accessToken() {
        return accessToken;
    }

    public GrantedOAuth2AccessToken grantedToken() {
        return grantedToken;
    }

    public OAuth2TokenDescriptor tokenDescriptor() {
        return tokenDescriptor;
    }
}

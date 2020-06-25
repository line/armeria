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

import javax.annotation.Nullable;

public class MockOAuth2AccessToken {

    public static final TokenDescriptor INACTIVE_TOKEN = TokenDescriptor.of("{\"active\":false}");

    private final String accessToken;
    private final AccessTokenCapsule tokenCapsule;
    private final TokenDescriptor tokenDescriptor;

    @SuppressWarnings("DynamicRegexReplaceableByCompiledPattern")
    public static MockOAuth2AccessToken generate(String clientId, @Nullable String username,
                                                 Duration expiresIn, @Nullable Map<String, String> extras,
                                                 @Nullable String... scope) {

        final Instant issuedAt = Instant.now();
        final TokenDescriptorBuilder builder = TokenDescriptor.builder(true).clientId(clientId)
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
        final TokenDescriptor descriptor = builder.build();
        final String accessToken = UUID.randomUUID().toString().replaceAll("-", "");
        final String refreshToken = UUID.randomUUID().toString().replaceAll("-", "");
        return new MockOAuth2AccessToken(accessToken, descriptor, refreshToken);
    }

    public MockOAuth2AccessToken(String accessToken, TokenDescriptor descriptor,
                                 @Nullable String refreshToken) {
        this.accessToken = accessToken;
        tokenDescriptor = descriptor;
        final AccessTokenCapsuleBuilder tokenCapsuleBuilder =
                new AccessTokenCapsuleBuilder(requireNonNull(accessToken, "accessToken"))
                        .scope(requireNonNull(descriptor, "descriptor").scope())
                        .issuedAt(requireNonNull(descriptor.issuedAt(), "issuedAt"));
        final String tokenType = descriptor.tokenType();
        if (tokenType != null) {
            tokenCapsuleBuilder.tokenType(tokenType);
        }
        final Duration expiresIn = descriptor.expiresIn();
        if (expiresIn != null) {
            tokenCapsuleBuilder.expiresIn(expiresIn);
        }
        if (refreshToken != null) {
            tokenCapsuleBuilder.refreshToken(refreshToken);
        }
        tokenCapsule = tokenCapsuleBuilder.build();
    }

    public String accessToken() {
        return accessToken;
    }

    public AccessTokenCapsule tokenCapsule() {
        return tokenCapsule;
    }

    public TokenDescriptor tokenDescriptor() {
        return tokenDescriptor;
    }
}

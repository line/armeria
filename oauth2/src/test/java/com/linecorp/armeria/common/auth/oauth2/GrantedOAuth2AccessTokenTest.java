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

import static com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken.SCOPE_SEPARATOR;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.annotation.Nullable;

public class GrantedOAuth2AccessTokenTest {

    @Test
    void testBuilder() throws Exception {
        final String accessToken = "2YotnFZFEjr1zCsicMWpAA";
        final String tokenType = "bearer";
        final Instant issuedAt = Instant.now();
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final String refreshToken = "tGzv3JOkF0XG5Qx2TlKWIA";
        final String[] scope = { "read", "write" };
        final Map<String, String> extras = Collections.singletonMap("example_parameter", "example_value");
        final String rawResponse =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"scope\":\"read write\"," +
                "\"example_parameter\":\"example_value\"}";

        final GrantedOAuth2AccessToken token = GrantedOAuth2AccessToken.builder(accessToken)
                                                                       .tokenType(tokenType)
                                                                       .issuedAt(issuedAt)
                                                                       .expiresIn(expiresIn)
                                                                       .refreshToken(refreshToken)
                                                                       .extras(extras)
                                                                       .scope(scope)
                                                                       .build();

        assertThat(token.accessToken()).isEqualTo(accessToken);
        assertThat(token.tokenType()).isEqualTo(tokenType);
        assertThat(token.issuedAt()).isEqualTo(issuedAt);
        assertThat(token.expiresIn()).isEqualTo(expiresIn);
        assertThat(token.expiresAt()).isEqualTo(issuedAt.plus(expiresIn));
        assertThat(token.isValid()).isTrue();
        assertThat(token.isValid(issuedAt.plus(Duration.ofSeconds(3000L)))).isTrue();
        assertThat(token.authorization()).isEqualTo("Bearer " + accessToken);
        assertThat(token.refreshToken()).isEqualTo(refreshToken);
        assertThat(token.scope()).isEqualTo(toScopeString(scope));
        assertThat(token.scopeSet()).contains(scope);
        assertThat(token.scopeSet()).doesNotContain("foo");
        assertThat(token.scopeSet()).containsExactly(scope);
        assertThat(token.extras()).isEqualTo(extras);
        assertThat(token.rawResponse()).isEqualTo(rawResponse);
    }

    @Test
    void testOfRawResponse1() throws Exception {
        final String accessToken = "2YotnFZFEjr1zCsicMWpAA";
        final String tokenType = "bearer";
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final String refreshToken = "tGzv3JOkF0XG5Qx2TlKWIA";
        final String[] scope = { "read", "write" };
        final Map<String, String> extras = Collections.singletonMap("example_parameter", "example_value");
        final String rawResponse =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"scope\":\"read write\"," +
                "\"example_parameter\":\"example_value\"}";

        final GrantedOAuth2AccessToken token = GrantedOAuth2AccessToken.parse(rawResponse, null);

        Thread.sleep(100);

        assertThat(token.accessToken()).isEqualTo(accessToken);
        assertThat(token.tokenType()).isEqualTo(tokenType);
        assertThat(token.issuedAt()).isBefore(Instant.now());
        assertThat(token.expiresIn()).isEqualTo(expiresIn);
        assertThat(token.expiresAt()).isEqualTo(token.issuedAt().plus(expiresIn));
        assertThat(token.isValid()).isTrue();
        assertThat(token.isValid(token.issuedAt().plus(Duration.ofSeconds(3000L)))).isTrue();
        assertThat(token.authorization()).isEqualTo("Bearer " + accessToken);
        assertThat(token.refreshToken()).isEqualTo(refreshToken);
        assertThat(token.scope()).isEqualTo(toScopeString(scope));
        assertThat(token.scopeSet()).contains(scope);
        assertThat(token.scopeSet()).doesNotContain("foo");
        assertThat(token.scopeSet()).containsExactly(scope);
        assertThat(token.extras()).isEqualTo(extras);
        assertThat(token.rawResponse()).isEqualTo(rawResponse);
    }

    @Test
    void testOfRawResponse2() throws Exception {
        final String accessToken = "2YotnFZFEjr1zCsicMWpAA";
        final String tokenType = "bearer";
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final String refreshToken = "tGzv3JOkF0XG5Qx2TlKWIA";
        final String[] scope = { "read", "write" };
        final Map<String, String> extras = Collections.singletonMap("example_parameter", "example_value");
        final String rawResponse =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"example_parameter\":\"example_value\"}";

        final GrantedOAuth2AccessToken token = GrantedOAuth2AccessToken.parse(rawResponse, "read write");

        Thread.sleep(100);

        assertThat(token.accessToken()).isEqualTo(accessToken);
        assertThat(token.tokenType()).isEqualTo(tokenType);
        assertThat(token.issuedAt()).isBefore(Instant.now());
        assertThat(token.expiresIn()).isEqualTo(expiresIn);
        assertThat(token.expiresAt()).isEqualTo(token.issuedAt().plus(expiresIn));
        assertThat(token.isValid()).isTrue();
        assertThat(token.isValid(token.issuedAt().plus(Duration.ofSeconds(3000L)))).isTrue();
        assertThat(token.authorization()).isEqualTo("Bearer " + accessToken);
        assertThat(token.refreshToken()).isEqualTo(refreshToken);
        assertThat(token.scope()).isEqualTo(toScopeString(scope));
        assertThat(token.scopeSet()).contains(scope);
        assertThat(token.scopeSet()).doesNotContain("foo");
        assertThat(token.scopeSet()).containsExactly(scope);
        assertThat(token.extras()).isEqualTo(extras);
        assertThat(token.rawResponse()).isEqualTo(rawResponse);
    }

    @Test
    void testToString() throws Exception {
        final String accessToken = "2YotnFZFEjr1zCsicMWpAA";
        final String tokenType = "bearer";
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final Instant issuedAt = Instant.parse("2010-01-01T10:15:30Z");
        final String refreshToken = "tGzv3JOkF0XG5Qx2TlKWIA";
        final String[] scope = { "read", "write" };
        final Map<String, String> extras = Collections.singletonMap("example_parameter", "example_value");
        final String toString =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"issued_at\":\"2010-01-01T10:15:30Z\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"scope\":\"read write\"," +
                "\"example_parameter\":\"example_value\"}";

        final GrantedOAuth2AccessToken token = GrantedOAuth2AccessToken.builder(accessToken)
                                                                       .tokenType(tokenType)
                                                                       .issuedAt(issuedAt)
                                                                       .expiresIn(expiresIn)
                                                                       .refreshToken(refreshToken)
                                                                       .extras(extras)
                                                                       .scope(scope)
                                                                       .build();

        assertThat(token.toString()).isEqualTo(toString);
    }

    @Test
    void testScope() throws Exception {
        final String[] scope = { "read", "write" };

        final String rawResponse1 =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"scope\":\"read write\"," +
                "\"example_parameter\":\"example_value\"}";
        final GrantedOAuth2AccessToken token1 = GrantedOAuth2AccessToken.parse(rawResponse1, null);
        assertThat(token1.scope()).isEqualTo(toScopeString(scope));
        assertThat(token1.scopeSet()).containsExactly(scope);

        final String rawResponse2 =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"example_parameter\":\"example_value\"}";
        final GrantedOAuth2AccessToken token2 = GrantedOAuth2AccessToken.parse(rawResponse2, "read write");
        assertThat(token2.scope()).isEqualTo(toScopeString(scope));
        assertThat(token2.scopeSet()).containsExactly(scope);

        final String rawResponse3 =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"scope\":\"read write\"," +
                "\"example_parameter\":\"example_value\"}";
        final GrantedOAuth2AccessToken token3 = GrantedOAuth2AccessToken.parse(rawResponse3, "foo");
        assertThat(token3.scope()).isEqualTo(toScopeString(scope));
        assertThat(token3.scopeSet()).containsExactly(scope);
    }

    @Test
    void testEquals() throws Exception {
        final String accessToken = "2YotnFZFEjr1zCsicMWpAA";
        final String tokenType = "bearer";
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final Instant issuedAt = Instant.parse("2010-01-01T10:15:30Z");
        final String refreshToken = "tGzv3JOkF0XG5Qx2TlKWIA";
        final String[] scope = { "read", "write" };
        final Map<String, String> extras = Collections.singletonMap("example_parameter", "example_value");

        final GrantedOAuth2AccessToken token1 = GrantedOAuth2AccessToken.builder(accessToken)
                                                                        .tokenType(tokenType)
                                                                        .issuedAt(issuedAt)
                                                                        .expiresIn(expiresIn)
                                                                        .refreshToken(refreshToken)
                                                                        .extras(extras)
                                                                        .scope(scope)
                                                                        .build();

        final GrantedOAuth2AccessToken token2 = GrantedOAuth2AccessToken.builder(accessToken)
                                                                        .tokenType(tokenType)
                                                                        .issuedAt(issuedAt)
                                                                        .expiresIn(expiresIn)
                                                                        .refreshToken(refreshToken)
                                                                        .extras(extras)
                                                                        .scope(scope)
                                                                        .build();

        assertThat(token2).isEqualTo(token1);

        final GrantedOAuth2AccessToken token3 = GrantedOAuth2AccessToken.builder(accessToken)
                                                                        .tokenType(tokenType)
                                                                        .issuedAt(issuedAt)
                                                                        .expiresIn(expiresIn)
                                                                        .refreshToken(refreshToken)
                                                                        .extras(extras)
                                                                        .scope("read")
                                                                        .build();

        assertThat(token3).isNotEqualTo(token1);

        final GrantedOAuth2AccessToken token4 = GrantedOAuth2AccessToken.builder(accessToken)
                                                                        .tokenType(tokenType)
                                                                        .issuedAt(Instant.now())
                                                                        .expiresIn(expiresIn)
                                                                        .refreshToken(refreshToken)
                                                                        .extras(extras)
                                                                        .scope(scope)
                                                                        .build();

        assertThat(token4).isEqualTo(token1);
    }

    @Nullable
    private static String toScopeString(String... scopes) {
        if (scopes.length == 0) {
            return null;
        }
        return String.join(SCOPE_SEPARATOR, scopes);
    }
}
